# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository. Design and status:
[ARCHITECTURE.md](ARCHITECTURE.md); file-by-file explanation: [CODE-EXPLANATION.md](CODE-EXPLANATION.md);
decisions: [WRITEUP.md](WRITEUP.md); load tests and tuning: [LOAD-TESTING.md](LOAD-TESTING.md);
deploying (Render + Aiven): [DEPLOYMENT.md](DEPLOYMENT.md).
This file holds the working rules, commands and lessons learned.

## Project in one paragraph

**ts-ticketing-system** is the "Seat Reservation at Scale" take-home (Paytm Money, Deploy &
Observe round): sell assigned seats under ~20,000 concurrent "book" requests with **no
double-sell, no per-user limit breach, no double-charge, zero 5xx**. It is **one** Spring Boot
3.5 application on Java 21 (`com.ts.ticketing`, single Maven module) on MySQL 8: token issuance,
request validation, JWT checks, per-caller rate limiting, admission control, shows and
reservations, all in one JVM. It deploys as one container: locally with `docker-compose.yml`
(app + MySQL), on Render's free tier with `render.yaml` and an Aiven MySQL. (Until 2026-10-10 it
was five Spring Cloud microservices; see lesson 26.) Requirements come from
`BRD — Seat Reservation at Scale.docx` (local only, git-ignored); ids such as BR-01, FR-03,
NFR-11, T-01 in comments refer to it.

## Commands

Maven needs a **JDK 21 or newer** (`--release 21` is pinned). On this machine only the JDK 21
runtime is installed, so build with JDK 25 (lesson 7):

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64

./mvnw test                                   # 83 tests (integration tests need Docker: Testcontainers)
./mvnw test -Dtest=ConcurrencyTest
./mvnw package -DskipTests                    # target/ticketing.jar
./mvnw spring-boot:run                        # needs JWT_SECRET, ADMIN_SECRET and the compose MySQL

docker compose up --build -d --wait           # app on :8080 + MySQL on :3308
docker compose down                           # keep data;  `down -v` wipes MySQL
./burst.sh                                    # 20k-request burst, 33 checks
./burst.sh http://localhost:8080 --requests 2000 --users 300 --hot-users 100 --concurrency 100   # Render-sized
java loadtest/LoadProbe.java http://localhost:8080 local-admin-secret 20000 1000   # capacity
```

Smoke check: `curl localhost:8080/health/ready` → `{"status":"UP"}`.

## Ports and config (current values)

| What | Value | Where set |
|---|---|---|
| App | `8080` (`PORT`; host `APP_HOST_PORT`) | `application.yml` `server.port`, `Dockerfile EXPOSE`, compose `ports` + healthcheck |
| MySQL | host `3308` (`MYSQL_HOST_PORT`), `mysql:3306` inside compose | compose, `DATABASE_URL` |
| Secrets | `JWT_SECRET` (≥ 32 bytes), `ADMIN_SECRET`, `DATABASE_PASSWORD` | env only; compose has local fallbacks; tests get them from the POM's surefire config |
| Profile | `prod` in the image | `Dockerfile` (`application-prod.yml`: no DB fallback, JSON logs) |
| Render | `render.yaml`: free plan, pool 5, admission 32 / 30 s, trust `X-Forwarded-For` | `render.yaml` |

If you change a port, change **every** place in its row (lesson 2).

## How configuration flows

1. `src/main/resources/application.yml` holds all settings; `application-prod.yml` overrides for
   the image (no local database fallback).
2. Environment variables resolve `${VAR:default}`; **secrets never go into the yml files**.
3. Tests use the same `application.yml`; surefire sets `JWT_SECRET`, `ADMIN_SECRET` and
   `SEAT_GAUGE_MAX_AGE=0ms` as system properties, exactly as an environment would. Override a
   value in tests through its env placeholder, not with a test yml (lesson 19).

## Code layout

```
pom.xml                    one module: Boot parent, versions, surefire test env
src/main/java/com/ts/ticketing/
  TicketingApplication     main; @ConfigurationPropertiesScan, @EnableScheduling
  auth/                    AuthController (POST /auth/token), IdentityProperties
  security/                JwtService, JwtProperties, Role, AuthenticatedUser, JwtAuthenticationFilter, SecurityConfig
  web/                     FilterConfig (filter order), RequestIdFilter, RequestValidationFilter,
                           RateLimitFilter + TokenBucketRateLimiter, EdgeProperties
  api/                     ErrorResponse, ApiException, GlobalExceptionHandler, JsonErrorWriter
  show/, reservation/      the business logic and its SQL
  observability/           AdmissionControlFilter, BookingMetrics, SeatGauges
  health/, config/         /health/*, Clock
src/main/resources/        application.yml, application-prod.yml, logback-spring.xml, db/migration/V1__init.sql
src/test/java/...          IntegrationTest, support/TestApi, support/Invariants, tests per package
Dockerfile                 build + JRE runtime with CDS archive (Render-ready)
docker-compose.yml         mysql + app;  .env.example for overrides
render.yaml                Render Blueprint (Aiven MySQL)
burst.sh, burst.py         BRD burst;  loadtest/LoadProbe.java capacity probe
```

Filter order (`web/FilterConfig`): request id → request validation → Spring Security →
rate limit → admission control → MVC.

## Rules for writing code here

These come from the BRD and are what the panel will probe. Do not trade them away.

1. **Atomic decisions live in SQL, not Java.** Explicit SQL through `JdbcTemplate`, no JPA, no
   read-then-write. Seat claims are a conditional `UPDATE … WHERE status = 'available'` checked by
   update count. The one sanctioned pre-read is `ReservationRepository.countUnavailable`: it may
   only **decline**, never grant, and must check the idempotency key first. Reading a show's
   immutable definition is fine.
2. **Lock seat rows in ascending label order** and retry MySQL 1213/1205 up to 2 times; keep the lock
   order reservations row → `user_show_counts` row → seats (sorted).
3. **Identity comes only from the token.** Controllers take `@AuthenticationPrincipal
   AuthenticatedUser`. Never trust a body `user_id` or any identity header.
4. **Domain declines are 4xx, never 5xx.** 409 `seat_taken` / `per_user_limit` /
   `idempotency_key_conflict`, 429 `overloaded` / `rate_limited`. Every error body is
   `ErrorResponse`, filters included (`JsonErrorWriter`). 5xx only means infrastructure is down.
5. **Money is integer paise**: `BIGINT` / `long` / JSON integers; never from the client.
6. **Metrics count only what happened**: increment after commit (or rollback for declines); gauges
   read from the DB at scrape time.
7. **JSON is snake_case** (`application.yml`); Java uses camelCase records.
8. **Configuration goes in `application.yml`, secrets in env.** A required secret is `${VAR:}`
   (empty fallback, lesson 1), never `${VAR}` and never a value in the repo. `application-prod.yml`
   carries no environment-specific defaults.
9. **Never log tokens or secrets.**
10. Dependencies are deliberate: no Lombok, no JPA, no Redis, no Spring Cloud.
11. **Transactions go through `TransactionTemplate` inside `LockRetry.run`.**
12. **Memory is sized, not guessed.** JVM flags, Tomcat connections and admission limits were
    measured together for 512 MB (LOAD-TESTING.md §5–6). Re-run the burst and check
    `docker stats` / `journalctl -k` before changing any of them.
13. **Cheap rejections go first.** A new edge check that needs no body or token goes in
    `RequestValidationFilter` (before security); one that needs the user goes after security.
    Register filters only in `FilterConfig`.
14. **One database, one owner.** Only this app touches the schema, and only through Flyway
    migrations (`V2__…`, never editing `V1`).

## Testing conventions

- Concurrency and API behaviour are tested against **real MySQL** (Testcontainers), never H2.
- Extend `IntegrationTest` (shared container and context; `Invariants.assertHold` after every
  test, T-10). Drive the API with `TestApi` over real HTTP; races with
  `TestApi.concurrently(n, i -> …)`; unique users with `TestApi.uniqueUser`. Assert metric
  **deltas** (the context is shared).
- Filters and limiters: unit-test with Mock servlet objects and injected time
  (`RateLimitFilterTest`, `AdmissionControlFilterTest`).
- The rate limit is on in tests (user burst 40): a test that fires more than ~40 requests from
  one user at once must spread them over users.
- Test classes end in `*Test` (no failsafe; `*IT` would be skipped).
- Metrics tests need `@AutoConfigureObservability` (`IntegrationTest` has it).
- Poll readiness instead of asserting it right after startup. Pin time with an injected `Clock`.

## Lessons learned (analysis log)

Lessons marked [microservices era] concern the former five-service build. They are kept because
the reasoning (memory per JVM, config precedence) still applies.

### 1. Unresolved `${VAR}` became a known admin secret (security bug, fixed)
`app.auth.admin-secret: ${ADMIN_SECRET}` with the env var unset binds the **literal string**
`${ADMIN_SECRET}`. That passes `@NotBlank`, so the app started, and the header
`X-Admin-Secret: ${ADMIN_SECRET}` minted an **admin token** (reproduced). The same thing made a
missing `JWT_SECRET` surface as a misleading "must be at least 32 bytes" error (the literal
is 13 chars). **Fix:** `${JWT_SECRET:}` / `${ADMIN_SECRET:}`, so a missing variable becomes empty
and fails `@NotBlank` with "must be set (env …)". Covered by `AuthPropertiesTest`.

### 2. Port change applied in only some places (fixed) [microservices era]
`application.yml` and the compose `ports` moved to 8085, but the compose healthcheck still
probed 8080 and the Dockerfile still said `EXPOSE 8080`, so the container could never become
healthy. **Fix:** the healthcheck uses `$${PORT:-8085}` (`$$` escapes compose interpolation, so
the container's own `PORT` is read) and `EXPOSE 8085`. See the ports table above.

### 3. Corrupt MySQL volume, slow Docker Desktop (fixed)
The first MySQL boot on an empty volume took about 4 minutes on Docker Desktop (1.8 GB VM).
The old healthcheck gave up after about 100 s, and an interrupted first boot left
`mysql-data` corrupt (`Data Dictionary initialization failed`). **Fix:** `start_period: 300s` +
`start_interval: 5s` on the MySQL healthcheck. If MySQL logs that error again, the volume is
broken: `docker compose down -v` (this wipes local data). Giving Docker Desktop more memory
helps; stop the compose stack before running the test suite on a small VM.

### 4. Port 3306 already in use on the dev machine
A local MySQL holds 3306, so the compose MySQL is published on 3308.

### 5. Test-environment gotchas
- `*IT` test classes were silently skipped (no failsafe), so test classes use the `*Test` suffix.
- `/metrics` returned 404 in tests until `@AutoConfigureObservability` was added.
- A readiness assert fired before the first DB connection on a slow host; the test now polls.

### 6. Clean-clone risk (fixed 2026-10-11)
The first commit (`def2e08`) did not contain `.gitignore`, `.gitattributes`, `.dockerignore`
or `.mvn/wrapper/`. Without `.mvn/wrapper/maven-wrapper.properties`, `./mvnw` and the Docker
build fail on a fresh clone (BRD risk R-08). All of them are committed since 2026-10-11.

### 7. JDK 21 compiler disappeared (worked around)
`./mvnw` failed with `release version 21 not supported`: `/usr/lib/jvm/java-21-openjdk-amd64` now
holds only the JRE (`openjdk-21-jre-headless`, no `javac`). Building with JDK 25 works because the
POM pins `--release 21`. The image is unaffected (Temurin 21 JDK). Installing `openjdk-21-jdk`
restores the original setup.

### 8. Jackson silently turned 250.5 into 250 (fixed)
Jackson's defaults accept a float for a `long` (truncating it) and a string for a number. For money
(BR-13) that is a silent corruption. **Fix:** `spring.jackson.deserialization.accept-float-as-int:
false` and `spring.jackson.mapper.allow-coercion-of-scalars: false`; `ShowApiTest` covers `250.5`,
`25000.0`, `"25000"` and `1e3`.

### 9. The load generator was the bottleneck (fixed in burst.py)
The first burst never finished: one Python process with 20k coroutines queued inside httpx's pool,
whose waiter handling is roughly quadratic, so the client sat at 100 % CPU while the server idled.
Even bounded, one Python process manages ~230 req/s. **Fix:** a semaphore caps in-flight requests
at the connection count, and `--workers` spreads the stampede over processes. Always check client
CPU before blaming the server; `loadtest/LoadProbe.java` measures the server alone.

### 10. Losers queued on sold seats' row locks (fixed)
99 % of stampede requests are declines on seats sold in the first second, yet each ran the full
transaction and waited on the hot row's lock just to roll back. **Fix:** the decline-only fast path
(rule 1). Through the burst client: 129 → 490 req/s, p99 38 s → 8.8 s.

### 11. 512 MB: the BRD's 75 % heap filled the container (fixed)
Native Memory Tracking showed ~150 MiB of non-heap plus ~45 MiB of glibc overhead, so a 371 MiB
heap couldn't fit. A heap histogram showed ~110 KB per request in flight (Tomcat char buffers,
parked virtual-thread stacks, Spring Security observation wrappers). **Fix:** heap 60 %, metaspace
and code-cache caps, `MALLOC_ARENA_MAX=2`, Spring Security observations off. The JRE image has no
`jcmd`: run the jar on the host JRE with `-XX:MaxRAM=512m` and attach JDK 25's `jcmd`, or add
`-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics` and
read the summary from `docker logs` after `docker stop`.

### 12. Too many connections ran the heap out; too few starved (fixed)
With `max-connections` 4,096 a 4,000-connection probe exhausted the heap (`ExitOnOutOfMemoryError`
→ compose restarted the app). At 500, backlogged connections starved behind busy keep-alive ones
(2,000 client timeouts, which a proxy would turn into 5xx). Capping the admission queue instead
made 83 % of requests 429 without saving memory. **Fix:** 1,000 connections, backlog 4,096,
`max-keep-alive-requests` 25. A 4,000-connection probe then completes with zero errors.

### 13. Python without ensurepip (worked around)
`python3 -m venv` fails on this machine (no `ensurepip`, no `pip`). `burst.sh` falls back to a
`python:3.13-slim` container with host networking, so it works anywhere Docker does.

### 14. Harmless startup warning
Flyway logs "MySQL 8.4 is newer than this version of Flyway and support has not been tested".
Migrations run fine; upgrade Flyway when Boot's managed version supports 8.4 officially.

### 15. identity-service OOM-killed by token minting (fixed) [microservices era]
A 2,000-token burst over ~1,000 gateway connections killed identity in a 320 MB container
(kernel: `Memory cgroup out of memory … anon-rss:314176kB`). Any Spring MVC JVM needs ~150 MB of
non-heap plus ~110 KB of heap per request in flight. **Fix:** 512 MB, the same JVM caps and Tomcat
bounds as booking. Docker's `OOMKilled` flag stayed `false` (cgroup v2): check `journalctl -k`.

### 16. The split grew booking's non-heap: 60 % heap no longer fit (fixed) [microservices era]
Config client, Eureka client, retry and AOP add classes; under 1,000 gateway connections booking
was OOM-killed at anon 514 MB. **Fix:** queue at the gateway, not inside booking: the gateway's
HTTP client pool allows **500 connections per service** (excess waits in the reactive gateway,
≤ 10 s, then 429 `overloaded`), and booking runs at **50 %** heap.

### 17. Netty's direct memory ignores the heap limit (fixed) [microservices era]
At 4,000 connections the gateway was OOM-killed (anon 487 MB) despite its heap cap: Netty pools
socket buffers off-heap, up to the heap size, one arena per CPU. **Fix:**
`-XX:MaxDirectMemorySize=64m -Dio.netty.allocator.numDirectArenas=2`, heap 45 %.

### 18. `eureka.client.enabled=false` breaks the Eureka *server* (fixed) [microservices era]
The parent POM disables the Eureka client for all tests; the Eureka server then misses
`ApplicationInfoManager` and won't start. discovery-service's POM sets it back to `true`.

### 19. Imported config beats the importing file (fixed) [microservices era]
Tests import `config-repo/booking-service.yml` from `config/application.yml`; a value set next to
that import (`seat-gauge-max-age: 0ms`) lost to the imported file's `500ms`, and a metrics test
read a cached gauge. **Fix:** override through the env placeholder (`SEAT_GAUGE_MAX_AGE=0ms` as a
surefire system property), exactly as production would.

### 20. Duplicate `X-Request-Id` through the gateway (fixed) [microservices era]
The gateway set the header and the proxied response carried booking's copy. **Fix:** set it in a
`beforeCommit` hook, which replaces the downstream value. `GatewayFlowTest` covers it.

### 21. Idempotency keys outlive containers
A smoke test reusing key `k1` for user `u1` got 409 `idempotency_key_conflict`: the MySQL volume
kept that user's key from an earlier session. That is correct (keys are per user, forever); use
fresh keys or `docker compose down -v` for a clean slate.

### 22. Stress limit on one host [microservices era]
At 4,000 concurrent connections the stack stays up (0 restarts, 0 dropped connections), but ~14 %
of requests get 504 `gateway_timeout` after 30 s: they waited inside a CPU-saturated gateway while
booking itself answered within 1.8 s. Beyond that, add CPU or instances.

### 23. 0.1 CPU made startup take 5 minutes (fixed)
At Render-free speed (`docker run --cpus 0.1`), the monolith took 309 s to become ready, and a
sleeping instance pays that on its first request. **Fix:** the Dockerfile records a Class Data
Sharing archive at build time (training run with `spring.context.exit=onRefresh`, Flyway off,
throwaway secrets; the lazy pool needs no DB). JIT is C1 only (`TieredStopAtLevel=1`). Result:
99 s. On a bigger plan, drop `TieredStopAtLevel` and `MaxRAM` from `JAVA_TOOL_OPTIONS`.

### 24. Slow CPU turned hot-seat 409s into 429s (fixed in render.yaml)
At 0.1 CPU, 100 simultaneous hot-seat requests couldn't pass the admission queue within 5 s.
Some losers got 429 `overloaded` instead of 409 `seat_taken`: still correct, but the burst failed
that check (32/33). **Fix:** `ADMISSION_ACQUIRE_TIMEOUT=30s` in `render.yaml`, so a slow host
queues instead of shedding: 33/33, 0 × 429. Keep the default 5 s where the CPU is real.

### 25. Reusing a Spring Boot module as a library [microservices era]
A Boot module's main jar is the repackaged fat jar (classes under `BOOT-INF/`), which can't be
used as a dependency. booking-service and identity-service also build a plain `lib`-classifier
jar (`maven-jar-plugin`, `application.yml` excluded so their config-server bootstrap doesn't leak
in), and monolith-app depends on those. The Maven reactor resolves them from the module's
`target/classes` even for `./mvnw test`.

### 26. Five microservices bought nothing the BRD needs (2026-10-10)
Every atomic decision was already in one MySQL owned by one service; the split added three
JVMs, a network hop per request, ~2.5 GB of memory and two single points of failure, and Render's
free plan runs one 512 MB service. **Fix:** one application. The gateway's edge features moved in
as servlet filters (`web/`), identity's endpoint as `auth/`, config-repo as `application.yml`.
Rate limiting runs after Spring Security, so its bucket key is the verified user id. Result:
83 tests pass; the 20k burst passes 33/33 (596 req/s, 412 MiB peak of 512 MiB, 0 restarts); at
0.1 CPU it is ready in 93 s. The microservices tree is backed up outside the repo
(`../ts-ticket-booking-system-microservices-backup-2026-10-10.tar.gz`), since it was never committed.

### 27. Round trips to a remote database
On Render the database (Aiven) is in another data centre, so every statement costs a network
round trip. Connector/J options in `application.yml` cut the per-transaction overhead:
server-side prepared statement cache, `useLocalSessionState` (no isolation/autocommit probes),
`elideSetAutoCommits`, `cacheServerConfiguration`. Hikari `keepalive-time` 60 s and
`max-lifetime` 25 min stop NAT/proxies from silently dropping idle connections. Pick the Render
region closest to the Aiven region.

## Running from IntelliJ

- SDK 21+ (25 works); Maven → "Use Maven wrapper"; Docker via the Unix socket.
- Database: `docker compose up -d --wait mysql`.
- Run `TicketingApplication` with environment `JWT_SECRET=…;ADMIN_SECRET=…`. Defaults point at
  `localhost:3308`. Stop the compose `app` container first (`docker compose stop app`).
- Tests: right-click `src/test/java` → Run (Docker must be running).
