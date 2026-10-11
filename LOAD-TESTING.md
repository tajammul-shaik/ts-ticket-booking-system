# Load testing

How ts-ticketing-system was load-tested, what the numbers were, what broke along the way and
what was changed because of it. Design is in [ARCHITECTURE.md](ARCHITECTURE.md), the reasoning
behind it in [WRITEUP.md](WRITEUP.md).

The work happened in phases: first as a single service (§4–5, measured 2026-10-05), then
split into microservices with all traffic through a gateway (§1, §6, measured 2026-10-06), then a
monolith build for Render's free tier (§11, 2026-10-09). On 2026-10-10 the project became **one
application** again, keeping the gateway's edge features in-process: **§12 has the current
numbers.** The earlier sections are kept as the record of why each setting is what it is; their
gateway/booking-service names refer to the former build.

---

## 1. Summary (microservices, through the gateway)

| Question | Answer |
|---|---|
| Correct under a 20,000-request burst? | **Yes.** `./burst.sh` through the gateway passes **33/33** checks: one winner for a 500-way hot seat, no seat sold twice, nobody over the limit, idempotent retries reserve once, API = client = metrics |
| Any 5xx? | **0** client-side and 0 in booking's `http_server_requests`; **0 transport errors** |
| Restarts / OOM kills during the burst | **0** in the final configuration |
| Throughput through `burst.sh` | ~300 req/s, limited by the Python client (~4.3 cores) on a laptop that also runs 5 JVMs + MySQL |
| Server capacity (JDK probe via gateway, 1,000 connections) | ~830 req/s, p99 5.8 s, **0 errors** |
| Stress: 4,000 concurrent connections | No crash, no dropped connection; ~14 % answered 504 `gateway_timeout` after 30 s (they waited inside the gateway); the rest 201/409 |
| Deadlocks | 0 lock retries |

```
== Hot-seat storm: 500 users -> A12 at once
  {'409 seat_taken': 499, '201': 1}
== Stampede: 20000 requests, 1000 connections, 6 client processes
  67.0s, 298 req/s
    409 seat_taken                 19834
    201                              166
...
  requests 20533 · confirmed 174 · replayed 19 · seat_taken 20333 · per_user_limit 6 · key_conflict 1
  shed 429 overloaded 0 · gateway 429 rate_limited 0 · 5xx 0 · transport errors 0
  final seats: available 14, held 0, confirmed 186
  checks: 33/33 passed
RESULT: PASS
```

Container peaks during that run: booking 506 MiB / 512, gateway 408, identity 370, discovery 240,
config 184, MySQL 132; CPU busy-averages: client 430 %, booking 185 %, gateway 137 %, identity
109 % (token minting), MySQL 32 %.

## 2. Tools

| Tool | What it is for | Run it |
|---|---|---|
| `burst.sh` → `burst.py` | **Correctness under load** (the BRD's burst script). Python asyncio + httpx. Exits non-zero if any check fails | `./burst.sh [GATEWAY_URL]` (default `http://localhost:8080`) |
| `loadtest/LoadProbe.java` | **Server capacity.** Same request mix as the stampede, from JDK `HttpClient` on virtual threads (much cheaper per request than Python). Reports throughput and latency, no checks | `java loadtest/LoadProbe.java <BASE_URL> <ADMIN_SECRET> [requests] [connections]` |
| `/metrics` | booking-service's server-side truth: `http_server_requests_seconds`, `hikaricp_connections_*`, `jvm_gc_*`, domain counters (routed by the gateway) | `curl <GATEWAY_URL>/metrics` |
| `/actuator/prometheus` on the gateway | The gateway's own: `gateway_rate_limited_total`, its `http_server_requests_seconds` | `curl <GATEWAY_URL>/actuator/prometheus` |
| `docker stats`, `/sys/fs/cgroup/memory.*`, `journalctl -k` | Container CPU and memory, the peak (`memory.peak`), kernel OOM kills | see §8 |
| Native Memory Tracking, `jcmd GC.class_histogram` | Where the JVM's memory goes | see §5.4 |

### 2.1 What `burst.sh` does

`burst.sh` runs `burst.py` with httpx from a local venv (`.burst-venv/`, created on first run).
If the local Python can't build one (no `ensurepip`/`pip`, as on the test machine), it runs the
script in a `python:3.13-slim` container instead (host networking on Linux;
`host.docker.internal` on Docker Desktop).

| Step | What happens | Pass condition |
|---|---|---|
| 1. Setup | Wait for `/health/ready` (up to 180 s, survives a cold start); admin token; create a show of 200 seats (rows A–J × 20, limit 4, ₹250); mint 2,000 user tokens | admin token has `role: admin`; all tokens minted |
| 2. Hot seat | 500 users, parked on one `asyncio.Event`, released together, all reserve `A12` | exactly one 201; 499 × 409 `seat_taken` |
| 3. Stampede | 20,000 reserves of 1–2 random seats (70 % one seat), half aimed at 5 hot seats, spread over rows A–I, from the 2,000 users, unique key each. Meanwhile a poller reads `GET /shows/{id}` every 0.5 s | `invariant_ok` true in every sample |
| 4. Idempotency | Fresh user: 20 parallel calls with one key on `J1`; then same key, seat `J2` | one reservation id, one 201 + 19 × 200 with `Idempotent-Replay: true`; then 409 `idempotency_key_conflict` |
| 5. Limit | Fresh user: 10 parallel single-seat requests (`J3`–`J12`) | ≤ 4 × 201, the rest 409 `per_user_limit` |
| 6. Spoofing, cancel | Body carries `user_id: victim`; another user cancels it; owner cancels; the seat is re-booked; owner cancels again | owned by the token user; 404; 200 `cancelled`; 201; 200 and the re-sold seat stays confirmed |
| 7. Reconcile | Compare the API, what the client saw, and `/metrics` | see the table below |
| 8. Outcome | Print totals; exit 1 if anything failed | |

Row J is kept out of the stampede so steps 4–6 have free seats.

Reconciliation checks (step 7):

| Check | Compares |
|---|---|
| `invariant_ok` after the burst | `available + held + confirmed = total_seats` |
| No seat sold twice | seats across all live 201 responses have no duplicates |
| No user above the limit | seats per user across live 201 responses ≤ limit |
| API confirmed count | `counts.confirmed` = seats in client-seen 201s |
| `reservations_confirmed_total{show_id}` | = number of 201 responses |
| `reservations_cancelled_total{show_id}` | = cancels performed |
| `seats_available/held/confirmed{show_id}` | = the API's counts (gauges read the DB at scrape time) |
| `reservations_declined_total{reason}` deltas | = 409/429/200-replay responses the client saw, per reason |
| `http_server_requests_seconds_count{status=~"5.."}` | = 0 since start (readiness 503s excluded: failing closed is by design) |
| Client-side 5xx | = 0 |

**Transport errors** (timeouts, resets, refused connections) are counted separately from HTTP
statuses (BRD R-10). They are not 5xx, but they hide outcomes, so the exact client/server
comparisons are skipped (and say so) when any occur.

Useful options: `--requests`, `--users`, `--hot-users`, `--concurrency` (connections, default
1,000), `--workers` (client processes, default half the CPUs, max 8), `--seed` (reproducible
stampede), `--timeout`, `--admin-secret` (default `$ADMIN_SECRET`, else the compose fallback).

## 3. Test environment

| Item | Value |
|---|---|
| Host | One laptop: 12 CPUs, 7 GB RAM, Linux, Docker 29.1 |
| Containers | Production images, `prod` profile, **no CPU limits**. Phase 1: one app container, 512 MB. Phase 2: config 320 MB, discovery 384 MB, identity/booking/gateway 512 MB each |
| Database | `mysql:8.4` container on the same host, default settings |
| Pool / admission | Hikari 10 connections; admission 64 in flight, 5 s wait; gateway pool 500 connections per service (phase 2) |
| Load generator | On the same host. It competes with the app and MySQL for CPU, which matters (§5.2) |

Numbers from a shared laptop are indicative, not a benchmark. A deployed instance with a
fractional CPU will be much slower (§9).

## 4. Single-service results (2026-10-05, app container directly on :8085)

### 4.1 `./burst.sh http://localhost:8085` (default settings)

```
== Hot-seat storm: 500 users -> A12 at once
  {'409 seat_taken': 499, '201': 1}
  [PASS] hot seat has exactly one winner  (1 x 201)
== Stampede: 20000 requests, 1000 connections, 6 client processes
  43.4s, 461 req/s
    409 seat_taken                 19839
    201                              161
  latency p50 1237 ms, p95 6661 ms, p99 9896 ms, max 22953 ms
  [PASS] invariant held during the stampede  (85 samples)
...
  requests                    20533
  confirmed (201)             169      replayed (200)        19
  declined seat_taken         20338    declined per_user_limit  6
  declined key_conflict       1        shed 429 overloaded       0
  5xx                         0        transport errors          0
  final seats                 available 14, held 0, confirmed 186
  checks: 33/33 passed
RESULT: PASS
```

During the stampede: client ~4.7 cores, app ~1.8 cores, MySQL ~0.3 cores. The client is the
bottleneck; its latencies include time its own processes spend scheduling (§5.2).

### 4.2 Server capacity (`LoadProbe`, 20,000 reserves)

| Connections | Throughput | p50 | p95 | p99 | max | Outcomes | Errors | Notes |
|---|---|---|---|---|---|---|---|---|
| 1,000 | **1,978 req/s** | 467 ms | 876 ms | 1.0 s | 1.2 s | 165 × 201, 19,835 × 409 | 0 | all 180 seats sold, invariant ok |
| 4,000 | 201 req/s | 526 ms | 69 s | 99 s | 99 s | 166 × 201, 19,834 × 409 | 0 | 3,000 connections wait in the accept backlog (§5.5) |

Server-side during the 1,000-connection run: Hikari checkouts averaged **0.6 ms**, nothing ever
pending; MySQL used about one core. The database is not the bottleneck.

### 4.3 Memory and GC (512 MB container)

| Run | Peak (`memory.peak`) | Anonymous memory after | Live heap after full GC | Full GCs | Restarts |
|---|---|---|---|---|---|
| `burst.sh` | 511 MiB | 465 MiB | 45 MiB | 2 (0.26 s) | 0 |
| probe, 1,000 connections | 512 MiB | 497 MiB | 166 MiB | 31 (4.8 s) | 0 |
| probe, 4,000 connections | 513 MiB | 480 MiB | 152 MiB | 70 (10.5 s) | 0 |

`memory.peak` includes page cache, which the kernel reclaims under pressure; the anonymous
(non-reclaimable) memory is what can trigger the OOM killer, and it stayed under the limit.

## 5. The tuning journey, single service (what broke and why)

Every row below passed its correctness checks. What changed is speed and memory safety.

| # | Change | Throughput | p99 | Finding |
|---|---|---|---|---|
| 1 | First run: one Python process, 20k coroutines | stalled (> 9 min) | — | Client at 100 % CPU, server idle |
| 2 | Client: bound in-flight requests | 129 req/s | 38 s | Client still 100 % of one core |
| 3 | Server: decline-only fast path | 490 req/s | 8.8 s | Hot-row lock queue gone |
| 4 | Client: 6 worker processes; JDK probe added | 1,964 req/s (probe) | 1.4 s | Server capacity measured separately |
| 5 | Memory: 75 % heap measured | — | — | Container filled to 497 of 512 MiB |
| 6 | 55 % heap, 4,096 connections, probe at 4,000 | — | — | **Heap OOM**: JVM exited, compose restarted it |
| 7 | 60 % heap, 500 connections | — | — | Memory fine, but 2,000 client timeouts (starvation) |
| 8 | Admission queue capped at 500 | 1,481 req/s | 3.8 s | 83 % of requests became 429: rejected |
| 9 | Final: 60 % heap, 1,000 connections, keep-alive rotation, no security observations | 1,978 req/s (probe) | 1.0 s | Zero errors at 1,000 and 4,000 connections |

### 5.1 The client was the first bottleneck

The first run never finished: the Python container sat at 100 % CPU while the app used 0.04 %.
All 20,000 coroutines were queued inside httpx's connection pool, and its waiter bookkeeping is
roughly quadratic in the queue length. **Fix (client):** an `asyncio.Semaphore` caps requests in
flight at the connection count, so the pool never has more waiters than connections.

That gave 129 req/s, with the client still pegged at one core: one Python process spends
~4 ms of CPU per request (measured: about 230 req/s at most even with 50 connections). **Fix:**
`--workers` spreads the stampede and token minting across processes (each with its own event
loop and pool). Throughput then scaled with workers (3 → 267, 6 → 303, 10 → 628 req/s on a noisy
run), confirming the client limit.

### 5.2 Measuring the server on its own

To separate server capacity from client cost, `loadtest/LoadProbe.java` sends the same mix from
the JDK `HttpClient` on virtual threads: **~1,960 req/s, p99 1.4 s, zero errors** at that stage.
Server-side `http_server_requests` meanwhile showed even the database-free `/auth/token` taking
26 ms on average under the Python run: the app was waiting for CPU on a host where the client
used ~4.7 of 12 cores. Conclusion: on one machine, `burst.sh` measures correctness; `LoadProbe`
measures capacity.

### 5.3 Hot-row lock queue → decline-only fast path

99 % of stampede requests are declines, and half of them target 5 hot seats that sell out in
the first second. In the original transaction every loser still inserted its reservation row,
updated its limit counter, then **queued on the sold seat's row lock** (`SELECT … FOR UPDATE`)
behind every other loser, only to roll back. Losers serialized on hot rows.

**Fix (server):** before opening the transaction, one non-locking read:

```sql
SELECT COUNT(*) FROM seats
WHERE show_id = ? AND seat_label IN (?, ?) AND status <> 'available'
```

If any requested seat is already taken in the committed state, the request is declined
`seat_taken` at once, without a transaction or a lock wait. Why this is safe:

- It can only **decline**, never grant. Every grant still goes through the locked conditional
  `UPDATE … WHERE status = 'available'`, so no double-sell is possible through it.
- A seat seen as taken really was taken at that instant, so the decline is a correct
  (linearizable) answer at the time of the read.
- A same-key retry of a request that already succeeded would also see its seats taken, so the
  fast path first looks up `(user_id, idempotency_key)`: replays still return 200 and a key
  reused with a different body still returns 409 `idempotency_key_conflict`.
- A request that would fail both the limit and the seat check may now get `seat_taken` instead
  of `per_user_limit`. Both are correct declines.

Result: 129 → 490 req/s through the Python client, p99 38 s → 8.8 s. The mutation test (§6)
shows the grant path is still what enforces correctness.

### 5.4 Memory: the 512 MB container

Under the 1,000-connection probe with the BRD's `-XX:MaxRAMPercentage=75`, the container's
anonymous memory reached **497 of 512 MiB**. Native Memory Tracking (same image, same limit)
broke it down:

| Area | MiB |
|---|---|
| Java heap (60 % run) | 308 |
| Metaspace | 57 |
| Code cache | 24 |
| Symbols | 21 |
| Shared class space (CDS) | 12 |
| Class | 10 |
| Arena chunks | 9 |
| Everything else NMT tracks | ~11 |
| **NMT total** | **452** |
| glibc allocator overhead NMT can't see | ~45 |

So non-heap costs ~190 MiB, and a 371 MiB heap (75 %) can't fit beside it. A heap class histogram
taken mid-burst showed what fills the heap with 1,000 requests in flight: ~42 MB of `char[]`
(Tomcat's per-request character buffers), ~30 MB of `StackChunk` (the stacks of ~1,000 parked
virtual threads, ~28 KB each), and ~10,000 Spring Security observation wrappers. **About 110 KB of
heap per request in flight**, on top of a ~50 MB baseline.

**Fixes (Dockerfile, application.yml):**

| Setting | Value | Why |
|---|---|---|
| `-XX:MaxRAMPercentage` | **60** (BRD: 75) | ≈307 MiB heap. At 55 % the old generation was too small for the ~165 MiB live set of 1,000 in-flight requests (64 full GCs, 16 s of pauses) |
| `-XX:MaxMetaspaceSize` / `-XX:ReservedCodeCacheSize` | 96m / 32m | Non-heap can't creep into the last few MiB |
| `MALLOC_ARENA_MAX` | 2 | glibc otherwise creates a malloc arena per host CPU |
| `management.observations.enable.spring.security` | false | Removes the per-filter observation objects (and stack depth); `http_server_requests` is unaffected |

The JVM picks SerialGC in a 512 MB container (not "server class"). That is right for a
fractional-CPU host; it is single-threaded, which is why full GCs show up as pauses under load.

### 5.5 How many connections fit

Since each request in flight costs ~110 KB, the number of connections the JVM holds is the real
memory bound:

| Tomcat `max-connections` | Probe at 4,000 connections | Verdict |
|---|---|---|
| 4,096 | Heap exhausted → `ExitOnOutOfMemoryError` → container restarted; ~19k transport errors | unsafe |
| 500 | Memory fine; 2,000 requests timed out client-side: connections in the accept backlog starved behind busy keep-alive connections | starves |
| 1,000 + keep-alive rotation (25 requests) | All 20,000 answered, zero errors; p95 69 s for connections that waited in the backlog | **chosen** |

Connections beyond 1,000 wait in the kernel accept backlog (`accept-count` 4,096, capped by
`net.core.somaxconn`), which costs no heap. `max-keep-alive-requests: 25` makes busy clients
reconnect now and then, so backlogged connections get a turn.

A tempting alternative, capping the admission **queue** (500 waiting reserve calls, anything
more gets an immediate 429), was tried and rejected: at 1,000 connections it turned 83 % of
requests into 429s while the live heap stayed ~185 MB, because the cost is per request in
flight anywhere in Tomcat, not per admission waiter. The `max-queued` setting remains, defaulting
to 1,000 (≥ connections, so dormant) as a guard for anyone who raises `max-connections`.

## 6. Microservices: what the split changed

Same correctness, new failure modes, all found by bursting through the gateway:

| # | Finding | Evidence | Fix |
|---|---|---|---|
| 1 | **identity-service OOM-killed** while minting 2,000 tokens over ~1,000 gateway connections | Kernel: `Memory cgroup out of memory: Killed process (java) anon-rss:314176kB` in a 320 MB container; gateway answered 503/500 meanwhile; ~1,100 users got no token → 401s | 512 MB, same JVM caps and Tomcat bounds as booking. Docker's `OOMKilled` flag stayed `false` (cgroup v2); `journalctl -k` is the reliable source |
| 2 | **booking-service OOM-killed** by the JDK probe at 1,000 connections, though the single-service sizing had handled that | anon-rss 514 MB. As a microservice it also loads the config client, Eureka client, retry and AOP: more non-heap, so 60 % heap no longer fit | Queue at the gateway instead: **≤ 500 connections per downstream service**, excess requests wait in the reactive gateway (a few KB each, not ~110 KB in Tomcat), ≤ 10 s then 429. booking heap **50 %** |
| 3 | **gateway OOM-killed** at 4,000 connections, heap notwithstanding | anon-rss 487 MB: Netty's pooled direct (off-heap) buffers may grow as large as the heap, one arena per CPU | `-XX:MaxDirectMemorySize=64m`, 2 Netty arenas, heap 45 % |
| 4 | Duplicate `X-Request-Id` response header | gateway set it and the proxied booking response carried a copy | set it in `beforeCommit`, replacing the downstream copy |
| 5 | 4,000 connections: ~14 % 504 after 30 s | booking's own max latency stayed 1.8 s; requests waited inside the gateway on a CPU-saturated host | documented limit for one host; scale booking/gateway or add CPU beyond it |
| 6 | Gateway retries | a restarting identity instance turned token requests into 503s | token issuance and GET routes retry twice on connection failures; state-changing calls never |

Stress matrix after the fixes (JDK probe through the gateway, 20,000 reserves):

| Connections | Throughput | p50 | p99 | Outcomes | Restarts |
|---|---|---|---|---|---|
| 1,000 | 831 req/s | 1.0 s | 5.8 s | 163 × 201, 19,837 × 409 | 0 |
| 4,000 | 447 req/s | 1.9 s | 33.9 s | 162 × 201, 17,008 × 409, 2,830 × 504 | 0 |

Memory peaks after those runs: booking 492 MiB (anon 463), gateway 491 (anon 436), identity 344.

**Throughput vs the single service.** The probe reached ~1,980 req/s directly against one service and
~830 req/s through the gateway on the same laptop. The extra hop costs CPU (the gateway uses ~1.4
cores), the host now runs five JVMs, and booking runs at a smaller heap. Correctness is identical.

## 7. Making sure the tests can fail

Passing tests prove little unless they can fail. With the seat update's guard removed
(`AND status = 'available'` dropped from `takeSeats`), `ConcurrencyTest.t01HotSeatHasExactlyOneWinner`
failed with `"201"=500L (expected: 1L)` and the post-test invariant check (T-10) flagged
confirmed seats without a matching reservation. The guard was restored and the suite passes
(now 83 tests across all modules).

## 8. How to reproduce

```bash
# 1. The stack (the app in a 512 MB container, like a small cloud instance, plus MySQL)
docker compose up --build -d --wait

# 2. Correctness under load (exit code 0 = PASS)
./burst.sh                                         # add --seed 7 for a repeatable stampede

# 3. Server capacity (JDK 21+)
java loadtest/LoadProbe.java http://localhost:8080 local-admin-secret 20000 1000

# 4. Watch it
docker stats
docker compose exec app sh -c 'cat /sys/fs/cgroup/memory.peak; grep ^anon /sys/fs/cgroup/memory.stat'
journalctl -k | grep 'Killed process'              # OOM kills (Docker's flag can miss them)
curl -s localhost:8080/metrics | grep -E '^(reservations_|seats_|admission_|requests_rate_limited|hikaricp_connections_pending)'
docker compose logs -f app | grep '"outcome"'
```

With your own `.env` secrets: `ADMIN_SECRET=<value> ./burst.sh`. Against a deployment:
`ADMIN_SECRET=<prod secret> ./burst.sh https://<app-host> --requests 2000 --users 300 --hot-users 100 --concurrency 100`
(warm it first; a free Render instance needs about 1.5 minutes to wake).

## 9. Limits and what to expect in production

- **CPU.** Every number here had up to 12 cores available. A free-tier instance has a fraction of
  one core (Render's free tier has advertised 0.1 CPU; confirm current limits), so expect a small
  fraction of this throughput. Correctness doesn't depend on speed: slow requests wait for an
  admission slot and get **429 `overloaded`** after 5 s instead of failing.
- **The host's proxy.** Behind Render, the platform proxy holds client connections. If it waits
  longer for the app than its own timeout, it answers 502/504 itself, and those would count as
  5xx (BRD R-01). Admission control's 5 s ceiling is what keeps the app's answers inside any
  reasonable proxy timeout. Burst the live URL before handing in, and read the outcome table's
  transport-error and 5xx lines.
- **Memory.** The JVM is sized for 512 MB (§5.4, §12). With more memory, raise
  `TOMCAT_MAX_CONNECTIONS` (requests in flight) and `MaxRAMPercentage` together, then re-run the burst.
- **More instances.** The app scales for correctness (all decisions in MySQL), but its counters,
  rate-limit buckets and admission limits become per instance.
- **Database.** A free managed MySQL has few connections and little CPU. Keep `DB_POOL_SIZE` small
  (5 for Aiven free) and lower `ADMISSION_MAX_IN_FLIGHT` with it (about 4–8 × the pool).
- **Counters are per instance and reset on restart** (BRD A-3). After a restart the reconcile step
  still passes, because each burst creates a fresh show and compares deltas.

## 10. Tunables

| Variable | Default | Effect |
|---|---|---|
| `DB_POOL_SIZE` | 10 | Hikari connections; keep within the DB plan's limit |
| `ADMISSION_MAX_IN_FLIGHT` | 64 | Reserve calls doing DB work at once (≈ 4–8 × pool) |
| `ADMISSION_MAX_QUEUED` | 1000 | Reserve calls allowed to wait for a slot; beyond it → 429 at once |
| `ADMISSION_ACQUIRE_TIMEOUT` | 5s | Longest wait for a slot before 429 (NFR-04) |
| `TOMCAT_MAX_CONNECTIONS` | 1000 | Connections held by the JVM, which is the memory bound (~110 KB per request in flight) |
| `TOMCAT_ACCEPT_COUNT` | 4096 | Kernel backlog beyond that (capped by `net.core.somaxconn`) |
| `TOMCAT_MAX_KEEP_ALIVE_REQUESTS` | 25 | Rotates keep-alive connections so backlogged clients get served |
| `DB_LOCK_WAIT_TIMEOUT_SECONDS` | 10 | `innodb_lock_wait_timeout` per session; a longer wait is error 1205 (retried, then 429) |
| `RATE_LIMIT_USER_RATE` / `RATE_LIMIT_USER_BURST` | 20 / 40 | Per-user token bucket (keyed by the verified user id) |
| `RATE_LIMIT_ANON_RATE` / `RATE_LIMIT_ANON_BURST` | 2000 / 5000 | Per-IP bucket for unauthenticated calls (token minting) |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | false | Key anonymous calls by `X-Forwarded-For` (true behind Render's proxy) |
| `JAVA_TOOL_OPTIONS` | Dockerfile | `MaxRAM=512m`, heap 60 %, metaspace/code cache caps, C1-only JIT, exit on OOM |

## 11. The monolith on Render's free tier (2026-10-09)

`monolith-app` (booking-service plus token issuance, one JVM, no gateway) was run in a container
limited to **0.1 CPU and 512 MB**, to match Render's free plan. MySQL ran in a separate container
(`docker run --cpus 0.1 --memory 512m`, or `docker-compose.monolith.yml` with `cpus: 0.1`).

### 11.1 Startup at 0.1 CPU

| Image | Ready after (first boot, includes Flyway) |
|---|---|
| Plain `java -jar` | **309 s** |
| + Class Data Sharing archive built in the image + `-XX:TieredStopAtLevel=1` | **99 s** (`Started … in 86 s`) |

A sleeping free instance pays this on its first request, so it matters.
- **Class Data Sharing:** the Dockerfile starts the app once at build time
  (`-XX:ArchiveClassesAtExit`, `spring.context.exit=onRefresh`, Flyway off, so no database is
  needed). Later starts map the saved classes instead of parsing them again.
- **C1 only:** the optimizing JIT compiler (C2) would compete with startup for the 0.1 CPU.

On a bigger plan, set `JAVA_TOOL_OPTIONS` without `TieredStopAtLevel` and `MaxRAM`.

### 11.2 Burst at 0.1 CPU

`./burst.sh <url> --requests 2000 --users 300 --hot-users 100 --concurrency 100` (2,133 requests).
Pool 5 and 32 in flight, as `render.yaml` sets them.

| `ADMISSION_ACQUIRE_TIMEOUT` | Checks | Hot-seat storm (100 users) | 429 `overloaded` | 5xx | p99 / max | Memory |
|---|---|---|---|---|---|---|
| 5s (the default) | **32/33** | 1 × 201; some losers 429 instead of 409 | 271 | 0 | 18.3 s / 27.3 s | 240 MiB |
| **30s** (`render.yaml`) | **33/33** | 1 × 201, 99 × 409 in 26 s | 0 | 0 | 22.9 s / 32.5 s | 240 MiB |

At 0.1 CPU a request can't get through the admission queue within 5 s. Declining it with 429 is
correct load shedding (BR-15), but the burst expects every hot-seat loser to get `409 seat_taken`.
A slow host should queue requests rather than shed them, so `render.yaml` allows 30 s. The whole
run took about 3.5 minutes. The default 20,000-request burst would take over 30 minutes at this
CPU, so use the smaller command above against the free tier.

### 11.3 Burst at full CPU (the same image, 512 MB)

The default `./burst.sh` (20,533 requests, 1,000 connections, pool 10, 64 in flight):

- **33/33 checks**, 0 × 429, 0 5xx, 0 transport errors
- p99 6.7 s, run time 49 s
- **`memory.peak` 434 MiB** of 512, 0 restarts

Without the Spring Cloud clients, the monolith's non-heap is the single service's again, so its
60 % heap fits (compare §6 #2).

## 12. One application (current build, 2026-10-10)

The single application: the monolith's booking and token code plus the gateway's request
validation and per-user rate limiting, in one JVM, one 512 MB container (`docker-compose.yml`).
Same machine as §3.

### 12.1 Full CPU, default burst

`./burst.sh` (20,533 requests, 1,000 connections, pool 10, 64 in flight, rate limit on):

| Metric | Value |
|---|---|
| Checks | **33/33** |
| 5xx / transport errors / restarts | **0 / 0 / 0** |
| 429 `overloaded` / `rate_limited` | 0 / 0 |
| Stampede | 33.5 s, **596 req/s** (client-side) |
| Latency | p50 0.8 s, p95 5.1 s, p99 6.7 s, max 14.1 s |
| Memory after the burst (`docker stats`) | 412 MiB of 512 |
| Ready after `up` | ~5 s (CDS archive) |

### 12.2 Render-free simulation (0.1 CPU)

`cpus: 0.1`, `DB_POOL_SIZE=5`, `ADMISSION_MAX_IN_FLIGHT=32`, `ADMISSION_ACQUIRE_TIMEOUT=30s`,
`RATE_LIMIT_TRUST_FORWARDED_FOR=true` (the `render.yaml` values), then
`./burst.sh http://localhost:8080 --requests 2000 --users 300 --hot-users 100 --concurrency 100`:

| Metric | Value |
|---|---|
| Ready after container start (Flyway included) | **93 s** |
| Checks | **33/33**; hot seat 1 × 201, 99 × 409 |
| 5xx / transport errors / restarts | 0 / 0 / 0 |
| 429 `overloaded` / `rate_limited` | 0 / 0 |
| Stampede | 2,000 requests in 118 s (17 req/s) |
| Latency | p50 4.7 s, p95 11.4 s, p99 18.4 s, max 26.4 s |
| Memory after the burst | 235 MiB of 512 |

MySQL ran locally at full CPU here; on Render the database is Aiven in another data centre, so add
one network round trip per statement (choose the closest Render region).

