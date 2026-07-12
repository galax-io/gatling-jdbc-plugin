# Feature Specification: Statement Concurrency & Resource-Safety Hardening

**Feature Branch**: `001-concurrency-hardening`

**Created**: 2026-07-13

**Status**: Draft

**Input**: User description: "https://github.com/galax-io/gatling-jdbc-plugin/milestone/12 — v1.2.0 Connection-pool deadlock & concurrency hardening (remaining open issues: #83, #100, #120, #121)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Correct parameter binding for parameterized queries under load (Priority: P1)

A performance engineer runs a load test where many virtual users execute parameterized
queries and inserts at the same time. Every statement must receive exactly the parameter
values the test author specified, in the positions they specified — regardless of how many
virtual users run concurrently or how the execution environment schedules work.

Today, the values of a single statement's parameters can be applied concurrently with each
other, so two parameters of one statement may be bound out of order or corrupt one another
when work is spread across threads. (Issue #120)

**Why this priority**: Silently corrupted parameter bindings invalidate load-test results
and can write wrong data to the system under test. This is the highest-severity class of
defect for a testing tool: the tool itself lies about what it tested.

**Independent Test**: Can be fully tested by running a parameterized statement with
multiple parameters through an instrumented binding layer that records overlapping
binding calls, and by verifying stored values against a real database.

**Acceptance Scenarios**:

1. **Given** a parameterized statement with two or more parameters, **When** the statement
   is prepared and executed on a multi-threaded execution environment, **Then** at most one
   parameter binding is in progress at any instant (no overlap) and every parameter
   position receives exactly its declared value, bound exactly once.
2. **Given** a load scenario inserting known distinct values through parameterized inserts,
   **When** the scenario completes against a real database, **Then** every row read back
   contains exactly the values the test author supplied — no swapped or corrupted values.
3. **Given** a parameterized statement whose binding fails (e.g., invalid value), **When**
   the failure occurs, **Then** the operation is reported as failed (KO) with the original
   error and no partially-bound statement is executed.

---

### User Story 2 - Reliable stored-procedure input/output registration under load (Priority: P2)

A performance engineer load-tests stored procedures that take input parameters and return
output parameters. Input binding and output registration for a single call must happen
one at a time and in a predictable order, so the driver never sees overlapping or
misordered registration on the same call.

Today, input binding and output registration for one stored-procedure call can run
concurrently, causing driver errors or values registered at wrong positions. (Issue #121)

**Why this priority**: Same defect class as User Story 1 (corrupted test execution), but
scoped to stored-procedure users — a narrower audience than plain parameterized queries.

**Independent Test**: Can be fully tested by instrumenting a stored-procedure call to
record overlapping binding/registration calls, and by invoking a real stored procedure
that returns output values and verifying the results.

**Acceptance Scenarios**:

1. **Given** a stored-procedure call with both input and output parameters, **When** the
   call is prepared on a multi-threaded execution environment, **Then** at most one
   binding or registration action is in progress at any instant, every input position is
   bound exactly once, and every output position is registered exactly once.
2. **Given** a real stored procedure with input and output parameters, **When** it is
   executed under load, **Then** every call returns the correct output values for its
   inputs.

---

### User Story 3 - Batch operations honor the configured query timeout (Priority: P3)

A performance engineer configures a query timeout on the protocol so that slow database
operations fail fast instead of stalling the load test. Batch operations must respect this
timeout exactly like single-statement operations already do.

Today, batch operations ignore the configured timeout: a slow batch can wait indefinitely
even when a timeout is configured. (Issue #83)

**Why this priority**: A hung batch stalls virtual users and can mask the very
degradation the load test exists to detect — but it corrupts liveness, not data, so it
ranks below the binding-correctness stories.

**Independent Test**: Can be fully tested by running a deliberately slow batch against a
real database with a short configured timeout and asserting the operation fails within
the timeout window.

**Acceptance Scenarios**:

1. **Given** a protocol configured with a query timeout of N seconds, **When** a batch
   operation exceeds N seconds, **Then** the operation is aborted and reported as failed
   (KO) rather than waiting indefinitely.
2. **Given** a protocol configured with a query timeout, **When** a batch completes within
   the timeout, **Then** it succeeds exactly as before (no behavior change for fast
   batches).
3. **Given** no query timeout is configured, **When** a batch runs, **Then** existing
   behavior is unchanged (no implicit timeout is introduced).

---

### User Story 4 - Resources are always released when an operation fails immediately (Priority: P4)

A performance engineer runs long soak tests. Every database resource the plugin acquires
(connections, statements) must be returned on every path — including when an operation
fails immediately upon starting, before any asynchronous work begins.

Today, if the work given to a managed resource fails synchronously (before deferred work
is set up), the resource's release step is skipped, leaking the resource. (Issue #100)

**Why this priority**: The leak requires a specific failure mode (immediate synchronous
failure) to trigger, so it is less frequent than the always-on defects above — but under
soak load, repeated leaks exhaust the connection pool and halt the test.

**Independent Test**: Can be fully tested by handing a managed resource a task that throws
immediately and asserting the resource is released exactly once and the original error is
preserved.

**Acceptance Scenarios**:

1. **Given** a managed resource whose consuming task fails immediately (synchronously),
   **When** the failure occurs, **Then** the resource is released exactly once and the
   caller observes the original error.
2. **Given** a managed resource whose consuming task fails asynchronously (existing
   behavior), **When** the failure occurs, **Then** the resource is still released exactly
   once (no regression).
3. **Given** a soak scenario where a fraction of operations fail immediately, **When** the
   scenario runs to completion, **Then** the connection pool retains full capacity (no
   leaked connections or statements).
4. **Given** a managed resource whose consuming task fails AND whose release step also
   fails with an ordinary (non-fatal) error, **When** the operation completes, **Then**
   the caller observes the original task error with the release error attached as a
   secondary (suppressed) failure, and release was attempted exactly once per resource.

---

### Edge Cases

- Statement with zero parameters: preparation must still succeed with no binding work.
- Statement with exactly one parameter: serialization requirement is trivially satisfied;
  no overhead regression expected.
- Single-threaded execution environment: behavior must be identical to today (the defects
  only manifest on multi-threaded executors, the fix must not depend on thread count).
- Stored-procedure call with only input or only output parameters: ordering guarantee
  still holds within the single kind.
- Query timeout of zero / not set: batches must not gain an implicit timeout.
- Timeout expiring mid-batch: partial batch effects follow the database's timeout
  semantics; the operation must be reported failed and its resources released.
- Release step itself failing after a synchronous task failure: for ordinary (non-fatal)
  release errors the original task error must be the one reported, with the release
  failure attached secondarily (suppressed), and release must not be attempted twice.
  Fatal JVM-level errors raised during release (e.g., out-of-memory, interruption) may
  take precedence over the original error — platform resource-management semantics.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: For a single parameterized statement, the system MUST apply all parameter
  bindings sequentially — at most one binding in progress at any instant — and MUST bind
  every parameter position exactly once with its declared value, on any execution
  environment. Relative order between distinct positions is not guaranteed and not
  required. (#120)
- **FR-002**: For a single stored-procedure call, the system MUST apply input parameter
  bindings and output parameter registrations sequentially — no overlap — binding every
  input position and registering every output position exactly once, on any execution
  environment. (#121)
- **FR-003**: Batch operations MUST honor the protocol-level query timeout exactly as
  single-statement operations do: a batch exceeding the configured timeout MUST be aborted
  and reported as failed. (#83)
- **FR-004**: When no query timeout is configured, batch behavior MUST remain unchanged
  (no implicit timeout).
- **FR-005**: A managed database resource MUST be released exactly once on every
  completion path of the task consuming it — success, asynchronous failure, and immediate
  synchronous failure. (#100)
- **FR-006**: When a consuming task fails synchronously, cleanup MUST still run, and the
  caller MUST observe the original failure whenever any cleanup failure is an ordinary
  (non-fatal) error — the cleanup failure is then attached as a secondary (suppressed)
  error. Fatal JVM-level errors raised during cleanup may take precedence (platform
  resource-management semantics).
- **FR-007**: All fixes MUST preserve the published Scala DSL, Java/Kotlin facade
  signatures, protocol defaults, and observable behavior for currently-working scenarios
  (Constitution Principle I).
- **FR-008**: Each fixed defect MUST be covered by a focused regression test exercising a
  real database path (H2 or PostgreSQL) where one exists, including concurrency
  instrumentation for FR-001/FR-002 and release-counting instrumentation (exactly-once
  per resource, including a failing-release case) for FR-005/FR-006 (Constitution
  Principle II; acceptance tests named in #83, #100, #120, #121).

### Key Entities

- **Statement operation**: One database action a virtual user performs (query, insert,
  batch, raw SQL, stored-procedure call); owns an ordered set of parameter bindings and an
  optional timeout.
- **Parameter binding**: The association of one user-supplied value with one position of a
  statement operation; the unit whose mutual overlap is being eliminated.
- **Managed resource**: A pooled database artifact (connection, statement) acquired for
  one operation and required to be released exactly once on all paths.
- **Query timeout**: Protocol-level maximum duration for a statement operation; currently
  enforced for single statements, extended by this feature to batches.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In an instrumented concurrency test, the maximum number of simultaneous
  parameter-binding actions observed on one statement is exactly 1, across repeated runs
  on a multi-threaded execution environment.
- **SC-002**: A load scenario writing known distinct values via parameterized statements
  and stored procedures reads back 100% correct values against a real database.
- **SC-003**: A batch operation exceeding a configured N-second timeout is reported failed
  in at most N seconds plus a small fixed margin, in 100% of runs; without a configured
  timeout, batch runtime behavior is unchanged.
- **SC-004**: After a soak scenario in which a fixed fraction of operations fail
  immediately, the connection pool reports zero leaked connections and each managed
  resource's release was invoked exactly once.
- **SC-005**: The full existing test suite and the example simulation (`DebugTest`) pass
  unchanged, demonstrating no regression for currently-working scenarios.
- **SC-006**: All four milestone issues (#83, #100, #120, #121) are closed by changes
  traceable to this specification, releasing milestone v1.2.0.

## Assumptions

- The audit baseline for all four defects is repository state
  `a8d0401bd92ea694f5f550dd279e61d5581408c3`; evidence links in the issues refer to that
  baseline and may have shifted line numbers on current `main`.
- Batch rollback masking (#84) and non-batch autoCommit behavior (#88) are explicitly out
  of scope — the milestone description tracks them as separate follow-ups.
- The connection-pool deadlock itself (#57) is already fixed and merged (#59); this
  feature covers only the remaining open concurrency defects in milestone v1.2.0.
- Sequential (serialized) parameter binding introduces no meaningful throughput loss:
  binding is cheap relative to statement execution, and correctness outranks marginal
  parallelism inside a single statement.
- No public API additions are required; all fixes are internal behavior corrections
  covered by Constitution Principle I (backward compatibility preserved).
- Binding order across distinct parameter positions is intentionally unspecified:
  correctness is "each position bound exactly once with its declared value". (Adjusted
  after adversarial review — the placeholder→index storage has no declaration-order
  contract, and drivers do not require one; requiring it would force a production
  rewrite with no user-visible benefit.)
- Error-preservation on cleanup failure follows the platform's resource-management
  severity semantics: ordinary cleanup errors never mask the original failure (they are
  suppressed); fatal JVM-level errors may take precedence. (Adjusted after adversarial
  review to match actual `scala.util.Using` behavior.)
