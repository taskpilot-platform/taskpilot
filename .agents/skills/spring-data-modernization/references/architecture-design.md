# Technical Architecture: Legacy Spring Data vs Modern Standards (3.x & 4.x)

## 1. Entity Reference Resolution: `getReferenceById(id)` vs `findById(id)`

### The Legacy Antipattern
In legacy Spring Boot applications, developers commonly write:
```java
UserEntity user = userRepository.findById(userId)
    .orElseThrow(() -> new EntityNotFoundException("User not found"));
UserSkillEntity link = new UserSkillEntity(user, skill, level);
userSkillRepository.save(link);
```
- **Database Cost**: Issues `SELECT u.id, u.email, u.password_hash, u.role, ... FROM users u WHERE u.id = ?`.
- **Latency Penalty**: Adds a network round-trip of 20–50ms (especially critical on Cloud databases like Neon or RDS).
- **Memory Overhead**: Hydrates the full entity state into the 1st level Hibernate Session cache.

### The Modern Standard
```java
// Assert existence if necessary, then obtain reference proxy
if (!userRepository.existsById(userId)) {
    throw new EntityNotFoundException("User not found");
}
UserEntity userProxy = userRepository.getReferenceById(userId);
UserSkillEntity link = new UserSkillEntity(userProxy, skill, level);
userSkillRepository.save(link);
```
- **Execution Mechanism**: `getReferenceById()` invokes `EntityManager.getReference()`, returning a ByteBuddy-generated Hibernate proxy.
- **SQL Emitted**: Exactly 0 SELECT queries. When `save(link)` runs, Hibernate inspects `userProxy.getId()` (which does not trigger initialization) and immediately binds the ID into the `INSERT INTO user_skills (user_id, ...) VALUES (?, ...)` SQL statement.

---

## 2. Deep & Infinite-Scroll Pagination: `Window<T>` vs `Page<T>`

### The Legacy Problem with Offset Pagination
```java
Page<ChatMessageEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);
```
1. **Count Query Overhead**: Every page request forces Spring Data to issue `SELECT COUNT(*) FROM chat_messages WHERE session_id = ?`. On large tables, this induces heavy CPU and I/O lock contention.
2. **Pagination Drift**: As new messages arrive in real-time at the top of the chat, `OFFSET 20 LIMIT 20` shifts, resulting in the user seeing duplicate or skipped messages upon scrolling.
3. **Degradation at Depth**: `OFFSET 5000 LIMIT 20` forces PostgreSQL to scan and discard 5,000 index tuples before reading 20 rows.

### The Modern Keyset Pagination Standard
```java
Window<ChatMessageEntity> findBySessionIdOrderByCreatedAtDescIdDesc(
    Long sessionId, 
    ScrollPosition position, 
    Limit limit
);
```
- **Execution Mechanism**: Leverages `ScrollPosition.keyset()`. The SQL query compiles to:
  ```sql
  SELECT * FROM chat_messages
  WHERE session_id = :sessionId
    AND (created_at < :lastCreatedAt OR (created_at = :lastCreatedAt AND id < :lastId))
  ORDER BY created_at DESC, id DESC
  LIMIT :limit;
  ```
- **Zero Count Queries**: `Window<T>` fetches exactly `limit + 1` rows to determine if more data exists, completely avoiding `COUNT(*)`.
- **Constant Time O(1)**: PostgreSQL utilizes the composite B-Tree index on `(session_id, created_at, id)` to seek directly to the continuation point.
- **Drift Immunity**: Keyset pagination ties traversal to immutable tuple coordinates, preventing duplicates regardless of newly inserted records.

---

## 3. Observability via `@Meta(comment = "...")`

### The Visibility Gap in Cloud Database Observability
Modern cloud PostgreSQL instances (Neon, Supabase, AWS Aurora) leverage `pg_stat_statements` to track slow queries and resource spikes:
```sql
SELECT t1_0.id, t1_0.title, ... FROM tasks t1_0 WHERE t1_0.project_id = ?;
```
Engineers cannot easily determine which Java service or repository issued this statement without full stack traces.

### Modern Query Commenting
```java
@Meta(comment = "TaskRepository.findAllByProjectId")
@Query("SELECT t FROM TaskEntity t WHERE t.projectId = :projectId")
List<TaskEntity> findAllByProjectId(@Param("projectId") Long projectId);
```
- **Generated SQL**:
  ```sql
  /* TaskRepository.findAllByProjectId */ SELECT t1_0.id, t1_0.title, ... FROM tasks t1_0 WHERE t1_0.project_id = ?;
  ```
- **Impact**: Provides instant tracing across Neon Console, Datadog APM, and PostgreSQL query log analyzers directly to the Java repository source.

---

## 4. Native Queries with `@NativeQuery`

Legacy `@Query(value = "...", nativeQuery = true)` conflates JPQL and native SQL.
Spring Data 3.4+ / 4.x introduces `@NativeQuery`:
```java
@NativeQuery("SELECT status, COUNT(*) FROM tasks WHERE project_id = :projectId GROUP BY status")
List<Object[]> summarizeTaskStatus(@Param("projectId") Long projectId);
```
- Cleaner declaration, unambiguous intent, and prepared for PostgreSQL-specific constructs such as `pgvector` operators (`<=>`).

---

## 5. Expression-Based Dynamic Sorting: `JpaSort.unsafe`

Spring Data's default `Sort.by("deadline")` strictly validates column names against Entity Java bean properties. When developers attempt to sort using PostgreSQL functions or expressions (such as `LENGTH(title)` or `deadline ASC NULLS LAST`), Spring Data throws `PropertyReferenceException: No property 'deadline ASC NULLS LAST' found for type 'Task'`.

Modern Spring Data JPA solves this with `JpaSort.unsafe`:
```java
Sort sort = JpaSort.unsafe("deadline ASC NULLS LAST");
taskRepository.findAll(sort);
```
- Instructs Spring Data to bypass strict entity property name introspection and emit the expression directly into the generated SQL `ORDER BY` clause.

---

## 6. Repository Fragment Composition

Rather than dumping all custom queries into a single monolithic `TaskRepositoryImpl.java` class:
```text
TaskRepository (Interface)
   ├── extends JpaRepository<TaskEntity, Long>
   ├── extends TaskSearchFragment
   └── extends TaskAnalyticsFragment
```
Spring Data automatically scans and composes discrete fragment implementations (`TaskSearchFragmentImpl`, `TaskAnalyticsFragmentImpl`) into the generated Spring repository bean proxy.
