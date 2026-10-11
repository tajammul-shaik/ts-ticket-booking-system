# ts-ticketing-system

Seat reservation at scale, as **one** Java 21 / Spring Boot 3.5 application on MySQL 8. It never
sells a seat twice, never lets a user go over the per-show limit, reserves exactly once per
idempotency key, and answers every domain outcome with a clean 4xx during a burst of ~20,000
requests.

```
client ──▶ ts-ticketing (:8080 / $PORT) ───────────────────────────────▶ MySQL 8 (local or Aiven)
           request id → validation → JWT → rate limit → admission → controllers → SQL
           POST /auth/token · /shows · /shows/{id}/reserve · /reservations/{id}/cancel · /health · /metrics
```

| Document | What's in it |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Design, request pipeline, data model, status |
| [CODE-EXPLANATION.md](CODE-EXPLANATION.md) | Every package and file explained |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Local Docker, a VM, and **Render free tier + Aiven MySQL** |
| [WRITEUP.md](WRITEUP.md) | Design decisions as precise pointers |
| [LOAD-TESTING.md](LOAD-TESTING.md) | Load-test method, results, tuning and why |
| [CLAUDE.md](CLAUDE.md) | Working rules and lessons learned |

## Requirements

- **Docker** with Compose v2. Two containers (app + MySQL); ~1 GB of memory is enough.
- To build or test outside Docker: **JDK 21+** (the Maven wrapper downloads Maven).

## Run it (clean clone)

```bash
git clone <repo-url> ts-ticketing-system && cd ts-ticketing-system
docker compose up --build -d --wait
curl localhost:8080/health/ready          # {"status":"UP"}: app up and database reachable
```

The first build takes a few minutes while Maven downloads dependencies. No `.env` is needed,
because compose has local-only defaults. To set your own secrets, ports or tuning,
run `cp .env.example .env` and edit it.

## Try the API

```bash
B=localhost:8080; J='Content-Type: application/json'
ADMIN=$(curl -s -XPOST $B/auth/token -H "$J" -H 'X-Admin-Secret: local-admin-secret' -d '{"user_id":"ops"}' | jq -r .token)
U1=$(curl -s -XPOST $B/auth/token -H "$J" -d '{"user_id":"u1"}' | jq -r .token)

SHOW=$(curl -s -XPOST $B/shows -H "$J" -H "Authorization: Bearer $ADMIN" \
       -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}' | jq -r .id)

curl -s -XPOST $B/shows/$SHOW/reserve -H "$J" -H "Authorization: Bearer $U1" \
     -d '{"seats":["A1","A2"],"idempotency_key":"k1"}'          # 201; again → 200 Idempotent-Replay
curl -s $B/shows/$SHOW -H "Authorization: Bearer $U1"           # counts, invariant_ok, seats
curl -s -XPOST $B/reservations/<reservation_id>/cancel -H "Authorization: Bearer $U1"
curl -s $B/metrics | grep -E '^(reservations_|seats_)'
```

## Burst test (bulk load + correctness)

```bash
./burst.sh                                   # 20,000 requests against http://localhost:8080
./burst.sh http://localhost:8080 --requests 5000 --seed 7
ADMIN_SECRET=<secret> ./burst.sh https://<your-app>.onrender.com --requests 2000 --users 300 --hot-users 100 --concurrency 100
./burst.sh --help
```

It runs 33 checks: one hot-seat winner, no double-sell, per-user limits, idempotency, spoofing,
cancel, and agreement between the API, the client and `/metrics`. It exits non-zero if any check
fails. It uses a local Python venv with httpx, or a `python:3.13-slim` container if your Python
can't create one.

Server capacity without the Python client's overhead (JDK 21+):
`java loadtest/LoadProbe.java http://localhost:8080 local-admin-secret 20000 1000`.

## Tests

```bash
./mvnw test          # 83 tests; the integration tests run against real MySQL (Testcontainers, needs Docker)
./mvnw test -Dtest=ConcurrencyTest
```

## API

| Endpoint | Auth | Success | Errors |
|---|---|---|---|
| `POST /auth/token` `{"user_id"}` | public; `X-Admin-Secret` → admin | 200 `{token, user_id, role, expires_at}` | 400 |
| `POST /shows` `{name, seats[], price_paise, per_user_limit?}` | admin | 201 show | 400, 401, 403 |
| `GET /shows/{id}` | user | 200 counts, `invariant_ok`, seats | 401, 404 |
| `POST /shows/{id}/reserve` `{seats[], idempotency_key}` | user | 201; replay 200 | 400, 401, 404, 409 `seat_taken`/`per_user_limit`/`idempotency_key_conflict`, 429 |
| `POST /reservations/{id}/cancel` | owner | 200 cancelled | 401, 404 |
| `GET /health/live`, `GET /health/ready` | public | 200 | 503 `not_ready` |
| `GET /metrics` | public | Prometheus | — |

Every error body is `{"error", "message", "request_id"}`. Edge errors: 401 `unauthorized`,
403 `forbidden`, 413 `payload_too_large`, 415 `unsupported_media_type`, 429 `rate_limited`
(per user or per IP), 429 `overloaded` (admission control or database contention).

## Configuration

All configuration is in `src/main/resources/application.yml` (plus `application-prod.yml`, which the
Docker image activates). Values written as `${VAR:default}` come from environment variables:

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | required (≥ 32 bytes) | compose has a local value |
| `ADMIN_SECRET` | required | compose has a local value |
| `DATABASE_URL` / `_USERNAME` / `_PASSWORD` | compose MySQL; required in prod | Aiven: `jdbc:mysql://<host>:<port>/defaultdb?sslMode=REQUIRED` |
| `DB_POOL_SIZE` | 10 | within the database plan's connection limit |
| `ADMISSION_MAX_IN_FLIGHT` / `_MAX_QUEUED` / `_ACQUIRE_TIMEOUT` | 64 / 1000 / 5s | reserve calls doing DB work / waiting / max wait |
| `RATE_LIMIT_USER_RATE` / `_USER_BURST` | 20 / 40 | per-user token bucket |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | false | `true` behind a proxy (Render) |
| `PORT` | 8080 | Render sets it |
| `APP_HOST_PORT`, `MYSQL_HOST_PORT` | 8080, 3308 | compose only |

## Run from the IDE

```bash
docker compose up -d --wait mysql
export JWT_SECRET=local-dev-jwt-secret-change-me-0123456789 ADMIN_SECRET=local-admin-secret
./mvnw spring-boot:run                     # or run TicketingApplication; defaults point at localhost:3308
```

## Stop

```bash
docker compose down          # keep the database
docker compose down -v       # also wipe it
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| App exits with `must be set (env JWT_SECRET)` | The secret isn't set in the app's environment |
| `port is already allocated` | Set `APP_HOST_PORT` / `MYSQL_HOST_PORT` in `.env` |
| MySQL logs `Data Dictionary initialization failed` | Interrupted first boot: `docker compose down -v`, then up again |
| Burst: "admin token" check fails | `ADMIN_SECRET` doesn't match the one the app runs with |
| 429 `rate_limited` in your own load test | Spread the load over more users or raise `RATE_LIMIT_*` |
