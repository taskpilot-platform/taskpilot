# Modern Spring Data Verification & Test Plan

This checklist outlines the mandatory test cases and verification criteria required to certify the Spring Data Modernization initiative.

---

## 1. Feature 11: Entity Reference (`getReferenceById`)

- [ ] **Proxy Generation Without Eager Fetch**: Calling `repo.getReferenceById(id)` must return an uninitialized Hibernate Proxy without issuing an immediate SQL `SELECT`.
- [ ] **Association Persistence**: Setting the returned proxy into a child entity (`userSkill.setUser(userProxy)`) and executing `repo.save(userSkill)` must emit only the target `INSERT` statement containing the foreign key `user_id`.
- [ ] **Lazy Initialization Exception Boundary**: Accessing non-ID properties on an uninitialized proxy outside an active transaction must throw standard `LazyInitializationException`, confirming it is indeed a lazy proxy and not eagerly loaded.
- [ ] **Zero Database Network Roundtrip**: Saving an association using `getReferenceById` saves 1 network roundtrip compared to `findById`.

---

## 2. Feature 4: Keyset Pagination (`Window<T>` & `ScrollPosition`)

- [ ] **Initial Page Retrieval**: Calling keyset query with `ScrollPosition.keyset()` on an initial position must return a valid `Window<T>` populated with up to `Limit` elements.
- [ ] **Next Position Derivation**: `window.positionAt(index)` or `window.hasNext()` must correctly generate the subsequent `KeysetScrollPosition` based on the sorting columns (`createdAt`, `id`).
- [ ] **Zero Count Query**: Calling `Window<T>` scrolling must NOT issue any `SELECT COUNT(*)` statements, preserving O(1) performance.
- [ ] **Consecutive Scroll Keyset Navigation**: Passing the next `ScrollPosition` into subsequent repository invocation retrieves consecutive items without skipping or duplication.

---

## 3. Feature 6: Query Observability (`@Meta(comment = "...")`)

- [ ] **Annotation Coverage**: 100% of custom `@Query` and `@NativeQuery` methods across all modules must bear `@Meta` annotations.
- [ ] **Naming Standard**: All `@Meta(comment)` values must adhere strictly to `<RepositoryInterfaceName>.<methodName>`.
- [ ] **SQL Comment Injection**: Hibernate must render the comment in the executed SQL stream (`/* RepositoryName.methodName */ SELECT ...`).

---

## 4. Feature 5: Dedicated `@NativeQuery`

- [ ] **Annotation Migration**: Any queries requiring raw PostgreSQL dialect features must utilize `@org.springframework.data.jpa.repository.NativeQuery`.
- [ ] **Parameter Binding**: Named parameters (`:param`) must bind cleanly in native SQL execution.

---

## 5. Feature 7: DB Function Sorting (`JpaSort.unsafe`)

- [ ] **Unsafe Sort Expression**: `JpaSort.unsafe(...)` with expressions like `deadline ASC NULLS LAST` must pass without throwing Spring Data's `PropertyReferenceException`.
- [ ] **SQL ORDER BY Generation**: The generated SQL must accurately include the custom expression in the `ORDER BY` clause.

---

## 6. Feature 10: Fragment Composition

- [ ] **Fragment Interface Definition**: Custom concern isolated in independent interface (`*Fragment`).
- [ ] **Fragment Implementation**: Concrete implementation suffixed with `FragmentImpl`.
- [ ] **Repository Inheritance**: Main repository interface cleanly extends both `JpaRepository` and the custom fragment.
- [ ] **Spring Bean Wire-up**: Spring Data JPA proxy generator successfully composites the fragment methods into the repository bean.

---

## 7. Regression & Build Gate

- [ ] **Full Reactor Build**: `./mvnw clean test` across all 7 Maven modules passes with 0 failures and 0 errors.
