# Write-up: ts-ticketing-system

Seat reservation at scale as one Spring Boot application on MySQL. Each point below is a claim
plus where it is enforced or proven. Requirement ids (BR, FR, NFR, T) refer to the BRD. Walkthrough
of the code: [CODE-EXPLANATION.md](CODE-EXPLANATION.md); measurements: [LOAD-TESTING.md](LOAD-TESTING.md).

---

## 1. Architecture

- **One application + MySQL** (`com.ts.ticketing`): token issuance, edge checks, shows and
  reservations in one JVM, one container. Locally `docker-compose.yml`; on Render `render.yaml`
  with an Aiven MySQL.
- **Correctness lives in one database.** Every atomic decision is a SQL statement on MySQL; the
  JVM holds no state that matters for correctness (show definitions are cached because they are
  immutable).
- **Why not microservices.** An earlier build split this into config server, Eureka, gateway,
  identity and booking. The split distributed no transaction, so it added nothing the BRD asks
  for. It did add three JVMs (~2.5 GB), a network hop per request and two single points of
  failure, and it didn't fit Render's free plan (one 512 MB instance). Every gateway feature was
  kept, as a servlet filter (§3). CLAUDE.md lesson 26.
- **Startup:** a Class Data Sharing archive built into the image plus C1-only JIT: ready in 93 s
  at 0.1 CPU instead of ~300 s (LOAD-TESTING.md §11).

## 2. Configuration

- **Single source**: `src/main/resources/application.yml`; the image's `prod` profile adds
  `application-prod.yml`, which blanks the database URL so `DATABASE_URL` must be set.
- **Secrets are never in the repo.** Files contain `${JWT_SECRET:}`; the environment supplies it.
- **Fail fast**: a missing secret is an empty string → `@NotBlank` → the app refuses to start
  (`JwtProperties`, `IdentityProperties`; tests `JwtPropertiesTest`, `IdentityPropertiesTest`).
- **Tests run the production file**: surefire only sets the env placeholders (test secrets,
  `SEAT_GAUGE_MAX_AGE=0ms`), as a deployment would.

## 3. Edge: validation, tokens, rate limits (`web/FilterConfig`)

Filters run cheapest rejection first: request id → request validation → Spring Security → rate
limit → admission control.
- **Request id first**: a safe client `X-Request-Id` is kept, else generated; it is in every log
  line, the response header and every error body (`RequestIdFilter`, `ObservabilityTest`).
- **Request validation without reading bodies** (`RequestValidationFilter`): 413 over 512 KB, 400
  for a bad `Idempotency-Key` header or a malformed path id; a non-JSON body gets 415 from MVC.
  None of these costs a JWT verification or a database call (`EdgeChecksTest`).
- **Token validation** (`SecurityConfig`, `JwtAuthenticationFilter`): HS256 signature + expiry +
  role; 401 `unauthorized`, 403 `forbidden` for non-admin `POST /shows` (`SecurityRulesTest`).
- **No identity spoofing**: the user id comes only from the verified token
  (`@AuthenticationPrincipal`); a `user_id` in a body is ignored (T-07). No identity headers exist
  to spoof.
- **Rate limiting** (`RateLimitFilter` + `TokenBucketRateLimiter`), after security so the key is
  the verified user id: 20/s, burst 40 per user; 2,000/s, burst 5,000 per IP when anonymous. 429
  `rate_limited` + `Retry-After`; `X-RateLimit-Remaining` on every response it checks. In memory
  because there is one instance and the BRD rules out Redis (`RateLimitFilterTest`, injected time).
- **Two different 429s**: `rate_limited` = one caller is too noisy; `overloaded` = everyone together
  is too much (admission control). The burst script reports them separately.
- **Errors are JSON everywhere**, filters included (`JsonErrorWriter`): 404 `not_found` for unknown
  routes, 405, 415, 413.

## 4. Identity

- `POST /auth/token {"user_id"}` → HS256 JWT, 24 h, `sub` = user id, `role` = user|admin
  (`auth/AuthController`).
- Admin only with the right `X-Admin-Secret`, compared in **constant time**; a wrong secret silently
  yields a user token (`AuthApiTest.wrongAdminSecretYieldsUserTokenNeverAdmin`).
- Stateless; tokens are never logged. A token from the endpoint drives the booking API end to end
  (`AuthApiTest.issuedTokensWorkAgainstTheBookingApi`).

## 5. The atomic decision

- **Every race-prone decision is one SQL statement judged by its update count**
  (`ReservationRepository`): no read-then-write in Java.
- **Seat**: `UPDATE seats … WHERE … AND status = 'available'` after
  `SELECT … ORDER BY seat_label FOR UPDATE`. InnoDB's locking reads make each waiter re-check the
  committed row, so a 500-way race has exactly one winner (T-01).
- **Limit**: `UPDATE user_show_counts SET held = held + n WHERE … AND held + n <= limit`, with the
  row locked first by `INSERT … ON DUPLICATE KEY UPDATE` (T-02). Checked before seats, so a seat
  update that succeeds can't be undone by a later step.
- **All-or-nothing**: fewer rows updated than seats requested → ROLLBACK → 409 `seat_taken` (T-05).
- **No deadlocks**: a single lock order (reservation row → counter row → seats by label); T-06 runs
  200 rounds of `[A,B]` vs `[B,A]` and asserts zero lock retries. 1213/1205 are still retried ≤ 2
  times (`LockRetry`), then 429.
- **Schema backs it**: `PRIMARY KEY (show_id, seat_label)`, one status column per seat (so
  `available + held + confirmed = total` holds structurally), `UNIQUE (user_id, idempotency_key)`,
  `CHECK (held >= 0)` (`V1__init.sql`).
- **The one pre-read can only decline**: a non-locking "is any seat taken?" check answers most
  stampede losers without a transaction or a lock wait. It never grants; it checks the idempotency
  key first so replays stay 200 (`ReservationService.reserve`). Effect: 129 → 490 req/s.
- **Proof the tests can fail**: removing `AND status = 'available'` makes T-01 report 500 winners.

## 6. Idempotency and money

- Key scoped per user, body or `Idempotency-Key` header (must agree). `request_hash =
  sha256(show_id:sorted seats)`.
- Same key + same body → **200** + `Idempotent-Replay: true`, original reservation. Same key + other
  body → **409** `idempotency_key_conflict`. Twin requests: the second blocks on the unique index,
  gets 1062, reads the committed row (T-03: 20 parallel calls → one reservation, one row).
- Declined requests store nothing, so the key can be retried fresh.
- Money: integer paise end to end (`BIGINT`, `long`, JSON integers); `amount = seats × price`
  computed server-side; Jackson rejects `250.5` and `"25000"` (`application.yml`,
  `ShowApiTest.moneyMustBeAnIntegerNeverAFloatOrString`).

## 7. Holds and expiry

- **Chosen** (BRD default): confirm on reserve + owner cancel. Cancel frees only seats still
  carrying that reservation id, so a re-sold seat is never resurrected (T-09); another user's
  reservation is 404, so its existence isn't revealed (T-08).
- **Next step, schema-ready** (`status='held'`, `held_until`): reserve to `held` with an expiry;
  confirm with `WHERE status='held' AND held_until > now`; treat expired holds as free in the
  reserve update, so correctness never depends on a sweeper.

## 8. Consistency vs availability

- **CP**: no database → readiness 503, reserves refused; nothing guesses.
- Readiness: `SELECT 1` with a hard 2 s deadline (`HealthController`); liveness never touches the DB.
- Flyway migrates before the port opens.
- Overload is shed, not failed: Tomcat holds ≤ 1,000 connections (the rest wait in the kernel
  backlog) → per-caller rate limit → admission (64 in flight, ≤ 5 s wait) → 429. Pool exhaustion
  and exhausted lock retries → 429.

## 9. Observability

- **Correlation**: one request id per request in every log line, the response header and the error body.
- **Logs**: JSON lines in `prod` (logstash encoder) with `request_id`; every reservation decision is logged as
  `user_id, show_id, seats, outcome, reason, latency_ms`; one access line per request.
- **Metrics** (`/metrics`): `reservations_confirmed_total{show_id}` and
  `reservations_cancelled_total{show_id}` after COMMIT; `reservations_declined_total{reason}` after
  ROLLBACK; `seats_available|held|confirmed{show_id}` read from MySQL at scrape time;
  `reservations_lock_retries_total`; `admission_in_flight`, `admission_waiting`;
  `requests_rate_limited_total`; `http_server_requests_seconds` (histogram);
  `hikaricp_connections_pending`.
- **Reconciliation is automated**: the burst compares API counts, client-seen responses and every
  counter and gauge (LOAD-TESTING.md §2).
- **Alerts (documented)**: any reserve 5xx for 1 min · invariant broken · readiness failing 1 min ·
  pending DB connections 5 min · lock retries rising · sustained 429 · p99 over threshold.

## 10. Load and memory (measured, not guessed)

- **20k burst, one app in 512 MB**: 20,533 requests, **33/33 checks, 0 5xx, 0 transport errors,
  0 restarts**, one hot-seat winner; 596 req/s through the Python client; 412 MiB peak
  (LOAD-TESTING.md §12).
- **Render-free simulation** (0.1 CPU, pool 5, 30 s admission wait): ready in 93 s; the
  2,000-request burst passes (LOAD-TESTING.md §12).
- **Per-request memory**: ~110 KB of heap per request in flight, so Tomcat holds at most 1,000
  connections and admission control bounds the DB work; the kernel backlog (4,096) queues the rest
  at no heap cost.
- **JVM sized from Native Memory Tracking**: 60 % heap, metaspace and code cache capped,
  `MALLOC_ARENA_MAX=2` (lesson 11).

## 11. Deviations from the BRD

| BRD | Built | Why |
|---|---|---|
| `MaxRAMPercentage=75` | 60 % + metaspace/code-cache caps | Measured OOM kills at the BRD value |
| Lock retries exhausted → 409 | 429 `overloaded` | Contention, not a state conflict |
| No pre-read | Decline-only fast path | Hot-row lock queue; it never grants |
| Error catalogue | + `rate_limited`, `payload_too_large`, `unsupported_media_type`, `not_found` | Edge outcomes, all JSON |

## 12. AI usage

Built with AI assistance (Claude Code): design discussion, code, tests, the burst and load-probe
scripts, running and analysing load tests, and these documents.

> _Author: adjust this section to state exactly how you used AI (BRD C-4)._

## 13. Next steps

1. Deploy and burst the public URL on Render + Aiven (`render.yaml`, DEPLOYMENT.md Part 3).
2. Time-boxed holds (§7).
3. Shared rate-limit store before running more than one instance.
4. Tracing (Micrometer Tracing + OpenTelemetry): spans from the filter pipeline down to SQL.
5. Wire the alerts into Prometheus/Grafana.
