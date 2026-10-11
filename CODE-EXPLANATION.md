# Code explanation

A guided tour of every package and file: what it does, why it exists, and how a request flows
through it. See [README.md](README.md) to run the system and [WRITEUP.md](WRITEUP.md) for the
design decisions in short form.

---

## 1. The system at a glance

One Spring Boot application (`com.ts.ticketing`, Java 21, virtual threads) and one MySQL 8
database. Locally it runs in two compose containers; on Render it is one web service plus an
Aiven MySQL.

```
client ──HTTP──▶ Tomcat ─▶ 1 RequestIdFilter ─▶ 2 RequestValidationFilter ─▶ 3 Spring Security (JWT)
                         ─▶ 4 RateLimitFilter ─▶ 5 AdmissionControlFilter (reserve only)
                         ─▶ controller ─▶ service (TransactionTemplate + LockRetry) ─▶ repository SQL ─▶ MySQL
```

### Life of a reserve request

1. `POST /shows/{id}/reserve` arrives on one of at most 1,000 Tomcat connections. Extra connections
   wait in the kernel backlog.
2. `RequestIdFilter` keeps the client's `X-Request-Id` if it has a safe shape, or generates one. It
   puts the id in the log MDC and the response header, and writes one access-log line at the end.
3. `RequestValidationFilter` checks the Content-Length (≤ 512 KB), the `Idempotency-Key` header
   and the show id's shape. It reads no body and parses no JWT.
4. Spring Security's `JwtAuthenticationFilter` verifies the bearer token and sets an
   `AuthenticatedUser` principal. Without one, the rules in `SecurityConfig` answer 401
   (or 403 for a non-admin `POST /shows`).
5. `RateLimitFilter` takes a token from the verified user's bucket (20/s, burst 40). If the bucket is
   empty: 429 `rate_limited` with `Retry-After`.
6. `AdmissionControlFilter` waits up to 5 s for one of 64 slots, else 429 `overloaded`.
7. `ReservationController` → `ReservationService` runs the fast decline or the transaction;
   `ReservationRepository` holds the SQL. The response is 201, 200 (replay), 409 or 429.

---

## 2. Root of the repository

| File | Purpose |
|---|---|
| `pom.xml` | One module. Spring Boot 3.5.16 parent; web, security, JDBC, actuator, Flyway, MySQL, jjwt, logstash encoder; Testcontainers for tests. Surefire passes test secrets and `SEAT_GAUGE_MAX_AGE=0ms` as env-style system properties |
| `mvnw`, `.mvn/wrapper/` | Maven wrapper: builds with a pinned Maven, nothing to install |
| `Dockerfile` | Build stage (dependencies in their own layer, no BuildKit cache mounts so Render can build it); JRE runtime, non-root, `prod` profile, `MALLOC_ARENA_MAX=2`, 512 MB JVM flags, and a **CDS archive** recorded at build time for fast starts |
| `docker-compose.yml` | MySQL + the app, health-ordered, 512 MB limit on the app, local-only default secrets |
| `.env.example` | Optional compose overrides (secrets, ports, tuning). Copy to `.env` |
| `render.yaml` | Render Blueprint: the Dockerfile as one free web service; secrets generated, Aiven DB values prompted, free-plan tuning |
| `burst.sh` / `burst.py` | The BRD burst test: 20,000 requests + 33 correctness checks |
| `loadtest/LoadProbe.java` | Server-capacity probe (JDK HttpClient), no checks |
| `.dockerignore`, `.gitignore`, `.gitattributes` | Keep build output, secrets and the BRD out of images and git |

---

## 3. `src/main/java/com/ts/ticketing`

### 3.1 `TicketingApplication`
`@SpringBootApplication` + `@ConfigurationPropertiesScan` (binds every `*Properties` record) +
`@EnableScheduling` (evicts idle rate-limit buckets every minute).

### 3.2 `auth`: tokens (FR-01)

| Class | What it does |
|---|---|
| `AuthController` | `POST /auth/token {"user_id"}` → `{token, user_id, role, expires_at}`. `X-Admin-Secret` equal to `ADMIN_SECRET` (constant-time compare) gives an admin token; a wrong secret silently gives a user token. The token is never logged |
| `IdentityProperties` | `app.identity.admin-secret`; `@NotBlank`, so a missing `ADMIN_SECRET` stops startup |

### 3.3 `security`: who may call what

| Class | What it does |
|---|---|
| `JwtService` | HS256 issue/verify with jjwt; rejects unsigned, tampered, expired, wrong-key and unknown-role tokens. Time comes from the injected `Clock` |
| `JwtProperties` | `app.jwt.secret` (required, ≥ 32 bytes) and `token-ttl` (24 h) |
| `Role`, `AuthenticatedUser` | `user`/`admin` claim ↔ `ROLE_*` authority; the principal controllers receive |
| `JwtAuthenticationFilter` | `Authorization: Bearer` → `AuthenticatedUser` in the security context; a bad token leaves the request anonymous |
| `SecurityConfig` | Stateless chain: `POST /auth/token`, health, `/metrics`, `/error` public; `POST /shows` admin; everything else authenticated; JSON 401/403. Also defines the `JwtService` bean |

### 3.4 `web`: the edge (formerly the gateway's job)

| Class | What it does |
|---|---|
| `FilterConfig` | Registers the servlet filters and their order (§1): request id, validation, (security), rate limit, admission |
| `RequestIdFilter` | NFR-13 correlation id + one access-log line per request |
| `RequestValidationFilter` | 413 `payload_too_large` over `app.edge.validation.max-body-bytes`; 400 for a bad `Idempotency-Key` header or a malformed id in `/shows/{id}` or `/reservations/{id}` |
| `RateLimitFilter` | Per verified user (`app.edge.rate-limit.user-*`) or per client IP (`anonymous-*`; `X-Forwarded-For` only if trusted). 429 `rate_limited` + `Retry-After`; `X-RateLimit-Remaining` on every response it checks; counter `requests_rate_limited_total`. Health and metrics are exempt |
| `TokenBucketRateLimiter` | In-memory token buckets with injected time; full (idle) buckets are evicted |
| `EdgeProperties` | `app.edge.*` |

### 3.5 `api`: error bodies

| Class | What it does |
|---|---|
| `ErrorResponse` | `{error, message, request_id}`: every non-2xx body |
| `ApiException` | A domain outcome with HTTP status and stable code (`seat_taken`, `per_user_limit`, …); thrown after the transaction rolled back |
| `GlobalExceptionHandler` | Maps everything to `ErrorResponse`: domain codes; validation → 400; non-JSON body → 415; pool timeout and exhausted lock retries → 429 `overloaded`; unexpected constraint → 409 `conflict`; Spring MVC 4xx keep their status; real bugs → 500 (logged) |
| `JsonErrorWriter` | Writes an `ErrorResponse` from servlet filters, where `@RestControllerAdvice` doesn't reach |

### 3.6 `show` and `reservation`: the business logic

| Class | What it does |
|---|---|
| `show/ShowController` | `POST /shows` (201 + `Location`), `GET /shows/{id}` |
| `show/ShowService` | Creates a show and all its seats in one transaction; builds the view (counts from ONE seat query, so they are a consistent snapshot; `invariant_ok`); caches each show's **immutable** definition (price, limit, labels) |
| `show/ShowRepository` | SQL: insert show, multi-row seat inserts (1,000 per statement), find show + labels, seat states |
| `show/SeatLabels` | Label rule (1–8 chars `[A-Za-z0-9_-]`), rejects duplicates, sorts ascending (the lock order) |
| `show/CreateShowRequest`, `Show`, `ShowView` | Request (Bean Validation, integer paise), definition, response |
| `reservation/ReservationController` | `POST /shows/{id}/reserve` (key from body or `Idempotency-Key` header; both must agree) → 201, or 200 + `Idempotent-Replay: true`; `POST /reservations/{id}/cancel`. The user is always `@AuthenticationPrincipal` |
| `reservation/ReservationService` | The transactions (§4). Metrics after commit/rollback; one structured log line per decision |
| `reservation/ReservationRepository` | Every atomic SQL statement; the service decides on update counts |
| `reservation/LockRetry` | Re-runs a whole transaction on MySQL 1213 (deadlock) / 1205 (lock wait timeout), at most twice, with 1–20 ms jitter |
| `reservation/RequestHash` | `sha256(show_id + ":" + sorted seats)` |

### 3.7 `observability`, `health`, `config`

| Class | What it does |
|---|---|
| `observability/AdmissionControlFilter` | Fair semaphore: 64 reserve calls in flight, waiters ≤ 5 s (and ≤ `max-queued`), else 429 `overloaded` |
| `observability/AdmissionProperties` | `app.admission.*` |
| `observability/BookingMetrics` | Counters `reservations_confirmed_total{show_id}`, `reservations_declined_total{reason}`, `reservations_cancelled_total{show_id}`, `reservations_lock_retries_total`; gauges `admission_in_flight`, `admission_waiting` |
| `observability/SeatGauges` | `seats_available/held/confirmed{show_id}` read from MySQL at scrape time (one grouped query, ≤ 500 ms snapshot) |
| `health/HealthController` | `/health/live` (never touches the DB) and `/health/ready` (`SELECT 1` on a virtual thread with a hard 2 s deadline; 503 `not_ready` on failure) |
| `config/ClockConfig` | Injectable `Clock` |

### 3.8 `src/main/resources`

| File | What it holds |
|---|---|
| `application.yml` | All configuration: server and Tomcat bounds, Jackson (snake_case, no number coercion), datasource + Hikari + Connector/J options, `app.jwt`, `app.identity`, `app.edge`, `app.admission`, `app.metrics`, management (`/metrics` only) |
| `application-prod.yml` | prod: no database fallback, INFO logging |
| `logback-spring.xml` | prod: JSON lines (logstash encoder) with `request_id`; otherwise a readable console pattern |
| `db/migration/V1__init.sql` | Flyway schema: `shows`, `seats` (PK `(show_id, seat_label)`, one status column), `reservations` (`UNIQUE (user_id, idempotency_key)`), `user_show_counts` (`CHECK (held >= 0)`), binary collation |

---

## 4. The reserve transaction (`ReservationService.reserve`)

```
validate (no transaction): show exists (cached definition) → 404; labels unique and known → 400
fast decline (no transaction): any requested seat not 'available'?
    → key already used by this user? replay 200 / 409 key conflict : 409 seat_taken
BEGIN (READ COMMITTED)
 1. INSERT INTO reservations …                          duplicate (user_id, key) → replay / conflict
 2. INSERT … user_show_counts ON DUPLICATE KEY UPDATE   (locks the counter row)
    UPDATE user_show_counts SET held = held + n WHERE … AND held + n <= limit    0 rows → 409 per_user_limit
 3. SELECT … FROM seats WHERE … ORDER BY seat_label FOR UPDATE                   (sorted lock order)
 4. UPDATE seats SET status='confirmed' … WHERE … AND status='available'         fewer rows → 409 seat_taken
COMMIT → 201
1213/1205 → retry whole transaction (≤ 2), then 429; no pool connection → 429
```

Cancel: `UPDATE reservations … WHERE id=? AND user_id=? AND status='confirmed'` (0 rows → 200
replay if it is the caller's, else 404), give back limit capacity, free only seats still carrying
this `reservation_id` (a re-sold seat is never touched).

---

## 5. Tests (83; integration tests on real MySQL 8.4 via Testcontainers)

| Test | Covers |
|---|---|
| `IntegrationTest` | Base: one shared container and context, real HTTP, **database invariants checked after every test** (T-10) |
| `support/TestApi`, `support/Invariants` | JDK HttpClient client; `concurrently(n, …)` releases n virtual threads through one start gate; the invariant queries |
| `reservation/ConcurrencyTest` | T-01 500-way hot seat, T-02 limit race, T-03 idempotent race, T-06 200 rounds of opposite-order requests with zero lock retries, mixed stampede |
| `reservation/ReservationApiTest` | T-04/05/07/08/09 and validation |
| `show/ShowApiTest` | Show creation rules, money coercion (`250.5`, `"25000"`, `1e3`) |
| `auth/AuthApiTest` | Token issuance, admin secret, 400s, request id, and a token from the endpoint driving the booking API |
| `security/SecurityRulesTest` | 401 / 403, public token endpoint |
| `web/EdgeChecksTest` | 415, 413, bad `Idempotency-Key`, bad path id, JSON 404, public probes, `X-RateLimit-Remaining` |
| `web/RateLimitFilterTest`, `web/TokenBucketRateLimiterTest` | Buckets per user / per IP with injected time; exemptions; `Retry-After` |
| `observability/ObservabilityTest` | Request ids; counters and gauges reconcile with the DB |
| `observability/AdmissionControlFilterTest` | Shedding on timeout and on a full queue |
| `health/HealthEndpointsTest` | T-11: stops its own DB container; ready fails closed, live stays up |
| `security/JwtServiceTest`, `JwtPropertiesTest`, `auth/IdentityPropertiesTest` | Token forgery cases; missing/short secrets stop startup |
| `reservation/LockRetryTest`, `RequestHashTest` | Retry classification; hash shape |

---

## 6. burst.sh / burst.py

`burst.sh [BASE_URL] [options]` runs `burst.py` from a local venv with httpx, or in a
`python:3.13-slim` container if the local Python can't make a venv. Default URL
`http://localhost:8080`. Steps: wait for readiness → admin token → 200-seat show → 2,000 user
tokens → 500-way hot-seat storm → 20,000-request stampede (with an invariant poller) → idempotency
→ per-user limit → spoofing and cancel → reconcile API vs client vs `/metrics` → outcome table and
exit code. `--workers` spreads the load over processes (one Python process manages a few hundred
requests per second). `429 rate_limited` and `429 overloaded` are reported separately; transport
errors are not 5xx. Details: [LOAD-TESTING.md](LOAD-TESTING.md).
