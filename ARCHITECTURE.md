# Architecture, design and status

ts-ticketing-system: seat reservation at scale, as **one Spring Boot application** on MySQL. This
document covers the design and the project status. File-by-file explanation:
[CODE-EXPLANATION.md](CODE-EXPLANATION.md); short decision notes: [WRITEUP.md](WRITEUP.md);
measurements: [LOAD-TESTING.md](LOAD-TESTING.md); working rules and lessons: [CLAUDE.md](CLAUDE.md).
Requirement ids refer to the BRD.

_Last updated: 2026-10-10._

---

## 1. Goals

| Guarantee | Meaning | Enforced by |
|---|---|---|
| No double-sell (BR-01, BR-02) | Exactly one winner per seat; the others get 409 `seat_taken` | Conditional `UPDATE … WHERE status='available'` under row locks |
| No limit breach (BR-03, BR-04) | A user never holds more than `per_user_limit` | Guarded `UPDATE user_show_counts … WHERE held + n <= limit` |
| No double-charge (BR-05, BR-06) | Same key + same body reserves once | `UNIQUE (user_id, idempotency_key)` + request hash |
| Zero 5xx for domain outcomes (BR-15) | Declines are 4xx; overload is 429 | Exception mapping, admission control, rate limiting |
| Identity only from tokens (BR-08) | No spoofing | The JWT subject is the only source of the user id; body `user_id` is ignored |
| Provable live (OBJ-5) | Metrics and logs reconcile with the DB | Counters after commit/rollback, gauges read from the DB, request id in every log line |

**Met locally:** the 20,000-request burst passes 33/33 checks with 0 5xx (596 req/s, 412 MiB peak
in a 512 MiB container). At Render-free settings (0.1 CPU) the smaller burst passes too
(LOAD-TESTING.md §11).

## 2. Why one application

The earlier split (config server, Eureka, gateway, identity, booking) bought nothing the BRD
asks for. Every atomic decision is already in one MySQL database owned by one service, and the
split added three JVMs, a network hop per request and two single points of failure. It also
needed ~2.5 GB of memory, which doesn't fit Render's free plan (one 512 MB instance). The single
application keeps every edge feature the gateway had (request validation, JWT checks, per-caller
rate limiting, JSON errors) as servlet filters in the same JVM:

| Was | Now |
|---|---|
| gateway `RequestIdFilter` + common's servlet copy | `web.RequestIdFilter` (one) |
| gateway `RequestValidationFilter` (reactive) | `web.RequestValidationFilter` (servlet; 415 comes from MVC) |
| gateway `TokenValidationFilter` + booking's Spring Security | `security.SecurityConfig` (one check, token endpoint public) |
| gateway `RateLimitFilter` | `web.RateLimitFilter`, after Spring Security, keyed by verified user id |
| gateway connection pool (500/service, queue ≤ 10 s) | Tomcat ≤ 1,000 connections + admission control (64 in flight, ≤ 5 s queue) |
| identity-service | `auth.AuthController` |
| config server + config-repo | `application.yml` + `application-prod.yml` |
| Eureka | not needed |

## 3. System context

```
                ┌──────────────────── one container (512 MB) ─────────────────────┐
 clients ──────▶│ Tomcat (virtual threads, ≤ 1,000 connections, backlog 4,096)   │
 burst.sh, curl │  1 RequestIdFilter        X-Request-Id, MDC, access log        │
                │  2 RequestValidationFilter 413 / 400 (no body read, no JWT)     │
                │  3 Spring Security        JWT → 401, POST /shows admin → 403    │
                │  4 RateLimitFilter        user 20/s burst 40, IP 2,000/5,000    │
                │  5 AdmissionControlFilter reserve: 64 in flight, queue ≤ 5 s    │
                │  6 controllers → services (TransactionTemplate + LockRetry)     │──Hikari 10──▶ MySQL 8
                └──────────────────────────────────────────────────────────────────┘   (Aiven on Render)
```

- One instance (the brief allows it). Correctness doesn't depend on that: every decision is in
  MySQL. More instances would each keep their own counters and rate-limit buckets.

## 4. Technology

| Choice | Why |
|---|---|
| Java 21, virtual threads | A request waiting on JDBC or a lock parks cheaply (NFR-06) |
| Spring Boot 3.5.16 (MVC, Security, JDBC, Actuator) | One mature stack; no Spring Cloud |
| JdbcTemplate, MySQL 8 InnoDB, READ COMMITTED, Flyway | Explicit SQL; row locks + constraints (NFR-11, NFR-14) |
| Connector/J statement caching, local session state | Fewer round trips per transaction to a remote database |
| jjwt 0.13 (HS256) | Issue and verify in one place |
| Micrometer + Prometheus, logstash encoder | Metrics and JSON logs with request ids |
| CDS archive, C1-only JIT in the image | Ready in ~100 s at 0.1 CPU instead of ~300 s (CLAUDE.md lesson 23) |
| Testcontainers MySQL 8.4 | Concurrency tests on the real engine |
| Not used | JPA, Lombok, Redis, Spring Cloud |

## 5. Packages (`com.ts.ticketing`)

| Package | Holds |
|---|---|
| `TicketingApplication` | Main class: properties scan, scheduling |
| `auth` | `AuthController` (`POST /auth/token`), `IdentityProperties` |
| `security` | `JwtService`, `JwtProperties`, `Role`, `AuthenticatedUser`, `JwtAuthenticationFilter`, `SecurityConfig` |
| `web` | Filter pipeline (`FilterConfig`), `RequestIdFilter`, `RequestValidationFilter`, `RateLimitFilter`, `TokenBucketRateLimiter`, `EdgeProperties` |
| `api` | `ErrorResponse`, `ApiException`, `GlobalExceptionHandler`, `JsonErrorWriter` |
| `show` | Create/get shows, seat labels, show definition cache |
| `reservation` | Reserve, cancel, `LockRetry`, request hash, the conditional SQL |
| `observability` | `AdmissionControlFilter`, `BookingMetrics`, `SeatGauges` |
| `health` | `/health/live`, `/health/ready` |
| `config` | `Clock` |

## 6. API

| Endpoint | Auth | Success | Declines |
|---|---|---|---|
| `POST /auth/token` | public (+ `X-Admin-Secret`) | 200 `{token, user_id, role, expires_at}` | 400 |
| `POST /shows` | admin | 201 + `Location` | 400, 401, 403 |
| `GET /shows/{id}` | user | 200 `{…, counts, invariant_ok, seats}` | 401, 404 |
| `POST /shows/{id}/reserve` | user | 201; replay 200 + `Idempotent-Replay` | 400, 401, 404, 409 ×3, 429 |
| `POST /reservations/{id}/cancel` | owner | 200 cancelled (idempotent) | 401, 404 |
| `GET /health/live`, `/health/ready` | public | 200 | 503 `not_ready` |
| `GET /metrics` | public | Prometheus | — |

Every error body has the form `{"error", "message", "request_id"}`.

## 7. Data model and the reserve transaction

`src/main/resources/db/migration/V1__init.sql`: `shows`, `seats` (PK `(show_id, seat_label)`, one
status column), `reservations` (`UNIQUE (user_id, idempotency_key)`), `user_show_counts`
(`CHECK (held >= 0)`), utf8mb4_bin. Every table has a primary key (Aiven enforces it).

```
fast decline (no tx): any requested seat not available? → replay/conflict by key, else 409 seat_taken
BEGIN READ COMMITTED
 1 INSERT reservations (claims the key; 1062 → replay 200 / 409 conflict)
 2 INSERT … user_show_counts ON DUPLICATE KEY UPDATE; UPDATE … held + n <= limit (0 rows → 409 per_user_limit)
 3 SELECT seats … ORDER BY seat_label FOR UPDATE
 4 UPDATE seats … AND status='available' (fewer rows → 409 seat_taken)
COMMIT → 201 · 1213/1205 → retry ≤ 2 then 429 · no pool connection → 429
```

Cancel: conditional `UPDATE reservations … status='confirmed'` → give back limit → free seats
`WHERE reservation_id = ?` only. Same lock order as reserve.

## 8. High concurrency: what does the work

| Technique | Effect |
|---|---|
| Decline-only fast path (non-locking `COUNT` read) | ~99 % of a stampede are losers on sold seats; they never take a row lock or a transaction (lesson 10) |
| Show definitions cached (immutable) | Two queries saved on every reserve |
| Sorted lock order, lock-wait timeout 10 s, retry ≤ 2 | No deadlocks between reserve calls; a stuck lock becomes 429, not a 50 s stall |
| Admission control (fair semaphore + bounded queue) | DB work is bounded at ≈ 6 × pool; excess waits ≤ 5 s, then 429 `overloaded` |
| Rate limit per verified user / per IP | One noisy caller can't consume everyone's capacity |
| Tomcat ≤ 1,000 connections, backlog 4,096, keep-alive rotation | Memory bounded (~110 KB per request in flight); queued connections cost no heap (lesson 12) |
| Virtual threads | Thousands of parked waits without thousands of OS threads |
| Connector/J: server-side prepared statement cache, `useLocalSessionState`, `elideSetAutoCommits`, `cacheServerConfiguration` | Fewer round trips per transaction; matters most when the DB is in another data centre (Render ↔ Aiven) |
| Hikari keepalive 60 s, max lifetime 25 min | Hosted MySQL behind NAT doesn't drop idle connections under us |
| JVM: 60 % heap, capped metaspace/code cache, `MALLOC_ARENA_MAX=2` | Fits 512 MB under burst (lesson 11) |

## 9. Configuration

- `application.yml` holds everything; `application-prod.yml` (the image's profile) removes the
  local database fallback.
- Env vars resolve `${VAR:default}`. Secrets come only from env: `JWT_SECRET`, `ADMIN_SECRET`,
  `DATABASE_PASSWORD`. If one is missing, the app refuses to start (lesson 1).
- Compose supplies local defaults so a clean clone runs; `.env` overrides them. Render:
  `render.yaml`.

## 10. Cross-cutting

| Concern | Design |
|---|---|
| Consistency vs availability | CP: DB down → readiness 503, refuse to sell |
| Load shedding | Rate limit 429 `rate_limited` → admission 429 `overloaded` → pool/lock 429 |
| Observability | One request id per request in logs and error bodies; JSON logs; domain metrics; `requests_rate_limited_total`; HTTP histograms |
| Security | HS256 ≥ 32-byte key from env; constant-time admin-secret compare; identity only from the token; tokens never logged |
| Startup | Flyway migrates before the port opens; readiness reflects the database |

## 11. Status

| Item | Status |
|---|---|
| Schema, shows, reserve, cancel, metrics, admission, burst, load tuning | ✅ |
| Edge features in-process: request id, validation, JWT, rate limiting, JSON errors | ✅ |
| One Dockerfile (CDS), compose (app + MySQL), `render.yaml` for Render + Aiven | ✅ |
| Tests: 83, the integration tests on real MySQL | ✅ |
| Burst: 33/33, 0 5xx locally; Render-free simulation (0.1 CPU) | ✅ |
| Public deployment and live burst | ⏳ `render.yaml` ready; needs the Render and Aiven accounts |
| Commit history | ⏳ nothing committed since `def2e08` |
| Time-boxed holds, shared rate-limit store, tracing | Next steps (WRITEUP.md) |

## 12. Risks

| Risk | Mitigation |
|---|---|
| The JVM is OOM-killed under a burst | Sized from measurements; Tomcat and admission bounds; `ExitOnOutOfMemoryError` + restart policy |
| One instance is a single point of failure | Restarts in seconds (CDS); all state is in MySQL; rate limits reset on restart |
| Read-then-write creeps in | Decisions in conditional SQL; hot-seat test + invariant checks after every test |
| Free-tier CPU too slow for a large burst | Admission queue waits 30 s on Render instead of shedding (lesson 24); run the 2,000-request burst there |
| Clean clone fails | `.mvn/wrapper` must be committed (lesson 6); `docker compose up --build` verified |
