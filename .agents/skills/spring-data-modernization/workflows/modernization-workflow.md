# Spring Data JPA Modernization Workflow

This workflow guides engineers and autonomous agents through the phased auditing, refactoring, and validation of Spring Data JPA modernization features in TaskPilot.

---

## Phase 1: Persistence Layer Audit & Cataloging

1. **Audit Entity Reference Usages**:
   - Identify all services and adapters where `repository.findById(id)` is followed immediately by setting the retrieved entity as a `@ManyToOne` reference on a child or junction entity.
   - Catalog candidate locations in `tracking/modernization-tracking.md`.

2. **Audit Pagination Access Patterns**:
   - Locate chronological feeds (`ChatMessageRepository`, `NotificationRepository`, `AiLogRepository`).
   - Assess UI consumption mode (Infinite scroll vs discrete page numbers).
   - Flag candidates for Keyset scrolling (`Window<T>` + `ScrollPosition`).

3. **Audit Query Observability**:
   - Search for all `@Query` annotations across repository interfaces.
   - Document whether query comments exist or are missing.

4. **Audit Native Queries & Custom Impls**:
   - Identify any `@Query(nativeQuery = true)` for migration to `@NativeQuery`.
   - Inspect custom repository implementations for potential fragment extraction.

---

## Phase 2: Refactoring Execution

### Step 2.1: Entity Reference Migration (`getReferenceById`)
- Verify whether the caller needs to assert existence beforehand. If existence verification is needed, prefer `repository.existsById(id)` or reuse auth context, then acquire proxy via `repository.getReferenceById(id)`.
- Replace `findById()` entity loading with `getReferenceById()` proxy generation.
- Ensure the proxy is not accessed outside of transactional boundaries before being attached to the target entity.

### Step 2.2: Keyset Pagination Implementation (`Window<T>` & `ScrollPosition`)
- Define repository method with return type `Window<T>` and arguments:
  ```java
  Window<T> findBy<Property>(..., ScrollPosition position, Limit limit);
  ```
- Specify explicit ordering on indexed columns (e.g. `OrderByCreatedAtDescIdDesc`).
- Ensure database index exists on the compound sorting keys (`created_at DESC, id DESC`).

### Step 2.3: Observability Annotation (`@Meta(comment = "...")`)
- Add `@Meta(comment = "<RepositoryName>.<methodName>")` to all `@Query` and `@NativeQuery` methods.
- Standardize naming conventions: `<EntityOrRepoName>.<methodName>`.

### Step 2.4: Dedicated `@NativeQuery` Adoption
- Replace `@Query(value = "...", nativeQuery = true)` with `@NativeQuery("...")`.
- Ensure native SQL queries adhere to PostgreSQL 16+ syntax.

### Step 2.5: Database Function Sorting (`JpaSort.unsafe`)
- Replace fragile string concatenation or rejected `Sort.by(...)` with `JpaSort.unsafe("<db_expression>")`.
- Validate against SQL injection by whitelisting allowed sort columns and operators.

### Step 2.6: Repository Fragment Composition
- Define specialized fragment interface: `public interface <Domain><Concern>Fragment`.
- Implement fragment: `public class <Domain><Concern>FragmentImpl implements <Domain><Concern>Fragment`.
- Inherit fragment in primary repository: `public interface <Domain>Repository extends JpaRepository<...>, <Domain><Concern>Fragment`.

---

## Phase 3: Automated Testing & Verification Gates

1. **Unit & Mapping Assertions**:
   - Verify that repository interfaces compile with `Window`, `ScrollPosition`, and `Limit`.
   - Verify `@Meta` annotations are discoverable via reflection.
2. **Integration & Behavioral Tests**:
   - Execute query scrolling tests with `ScrollPosition.keyset()`.
   - Verify zero redundant SELECT statements when persisting child entities with `getReferenceById`.
3. **Full Reactor Build**:
   - Run `./mvnw clean test` across all reactor modules.

---

## Phase 4: Production Rollout & Observability Audit

1. **Verify PostgreSQL Logs**:
   - Check Neon query insights or local PostgreSQL log for query comments (`/* Repository.method */`).
2. **Commit & Push**:
   - Stage, commit, and push changes to GitHub main branch.
