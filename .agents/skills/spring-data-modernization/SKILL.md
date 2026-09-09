---
name: spring-data-modernization
description: Govern, implement, and verify the adoption of Spring Data 3.x/4.x and Spring Boot 4.x Modern Standards across TaskPilot persistence layers, including Entity References (getReferenceById), Keyset Pagination (Window and ScrollPosition), Observability Comments (@Meta), Dedicated @NativeQuery, Expression-based Sorting (JpaSort.unsafe), and Repository Fragment Composition.
---

# Spring Data JPA 3.x / 4.x Modernization Skill

This skill governs the systematic modernization of Spring Data JPA persistence patterns across the TaskPilot platform from legacy (Spring Data 1.x/2.x) idioms to the official modern standards introduced in Spring Data 3.x and 4.x (Java 25, Spring Boot 4.1+).

---

## 🏛️ Core Architectural Invariants (Non-Negotiable)

1. **Zero Redundant SELECT Queries for Entity References (`repo.getReferenceById(id)`)**:
   - Whenever an entity relation (`@ManyToOne` or associative join entity) is populated solely to establish a foreign key linkage (`user_id`, `project_id`, `session_id`), the service layer must never issue `findById()` or load heavy entity graphs into the persistence context.
   - Services must acquire lightweight proxy handles via `repository.getReferenceById(id)` to eliminate network round-trips to PostgreSQL.

2. **Keyset Pagination for High-Churn Feeds (`Window<T>` & `ScrollPosition`)**:
   - High-throughput chronological streams (AI Chat History, In-App Notifications, Activity Streams) must avoid standard Offset pagination (`Pageable`, `OFFSET N LIMIT M`).
   - Repository streaming queries must utilize Spring Data Keyset Pagination (`Window<T>`, `ScrollPosition.keyset()`, `Limit`) to ensure O(1) indexed seeks, zero `SELECT COUNT(*)` overhead, and immunity against pagination drift (duplicated or skipped items during concurrent insertions).

3. **Production Observability & SQL Query Provenance (`@Meta(comment = "...")`)**:
   - All explicitly defined `@Query` and `@NativeQuery` methods must be annotated with `@Meta(comment = "RepositoryName.methodName")`.
   - This injects structured SQL comments (`/* RepositoryName.methodName */ SELECT ...`) that surface directly in PostgreSQL `pg_stat_statements`, Neon Console query logs, and APM tracing tools for immediate slow-query diagnosis.

4. **Explicit Separation of Native SQL via `@NativeQuery`**:
   - Native PostgreSQL queries (e.g. pgvector distance operators, JSONB path operations, PostgreSQL system functions) must use the dedicated `@NativeQuery` annotation rather than legacy `@Query(value = "...", nativeQuery = true)`.

5. **Safe Database Function Sorting (`JpaSort.unsafe(...)`)**:
   - Dynamic sorting involving database functions (e.g. `LENGTH(title)`, `NULLS LAST`, custom PostgreSQL collations) must use `JpaSort.unsafe(...)` rather than standard `Sort.by(...)` to prevent `PropertyReferenceException` while strictly sanitizing expression inputs.

6. **Modular Repository Fragment Composition**:
   - Complex repository logic spanning distinct functional concerns (e.g. AHP ranking, advanced full-text search, statistical analytics) must be decoupled into independent fragment interfaces and implementations (`*Fragment`, `*FragmentImpl`) rather than monolithic repository implementation classes.

---

## 📂 Deliverables & Governance Structure

```text
taskpilot/
├── .agents/skills/spring-data-modernization/
│   ├── SKILL.md                               # This master governance document
│   ├── workflows/modernization-workflow.md     # Step-by-step audit, refactoring & rollout playbook
│   ├── checklists/test-and-verification-plan.md# Verification gates, benchmark criteria & assertions
│   ├── tracking/modernization-tracking.md      # Feature-by-feature implementation matrix & status
│   └── references/architecture-design.md       # Technical deep-dive: Legacy vs Modern Spring Data 4.x
report/
└── docs/spring-data-modernization/
    ├── PLAN.md                                 # High-level rollout roadmap and risk matrix
    ├── ACCEPTANCE_CRITERIA.md                  # Verifiable acceptance gates
    ├── STATUS.md                               # Real-time implementation status
    ├── DECISIONS.md                            # Architectural Decision Records (ADRs)
    ├── VERIFICATION.md                         # Test logs, verification commands, and proof
    └── UAT.md                                  # User Acceptance Testing & smoke validation
```
