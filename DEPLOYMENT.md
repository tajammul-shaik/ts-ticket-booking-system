# Deployment guide

How to run ts-ticketing-system with Docker locally (Part 1), on any VM or container platform
(Part 2), and on **Render's free tier with an Aiven MySQL database** (Part 3). It is one
container plus a MySQL database in every case. Design: [ARCHITECTURE.md](ARCHITECTURE.md).

## Configuration

Everything is in `src/main/resources/application.yml`; the image runs profile `prod`
(`application-prod.yml`: no local database fallback, JSON logs). Per-environment values come
from environment variables:

| Variable | Required | Notes |
|---|---|---|
| `JWT_SECRET` | **yes** | ≥ 32 bytes: `openssl rand -base64 48` |
| `ADMIN_SECRET` | **yes** | `openssl rand -hex 24` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | **yes in prod** | `jdbc:mysql://host:3306/db`; managed MySQL adds `?sslMode=REQUIRED` |
| `DB_POOL_SIZE` | no (10) | within the database plan's connection limit |
| `ADMISSION_MAX_IN_FLIGHT` / `_MAX_QUEUED` / `_ACQUIRE_TIMEOUT` | no (64 / 1000 / 5s) | in-flight ≈ 4–8 × pool; 30s on a slow CPU |
| `RATE_LIMIT_USER_RATE` / `_USER_BURST` / `_ANON_RATE` / `_ANON_BURST` | no (20 / 40 / 2000 / 5000) | per-user / per-IP token buckets |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | no (false) | `true` behind a proxy that sets `X-Forwarded-For` (Render, Caddy, a load balancer) |
| `PORT` | no (8080) | platforms like Render set it |
| `JAVA_TOOL_OPTIONS` | no | the image sets 512 MB / C1-only flags; override on bigger plans |

A missing secret stops the app at startup with `must be set (env …)`.

---

## Part 1: Local with Docker

```bash
docker compose up --build -d --wait            # app + MySQL; --wait returns when both are healthy
curl localhost:8080/health/ready               # {"status":"UP"}
./burst.sh                                     # 33 checks; exit 0 = PASS
```

Optional: `cp .env.example .env` and set your own secrets, ports or tuning before `up`.

| Task | Command |
|---|---|
| Logs (JSON lines) | `docker compose logs -f app` |
| Follow one request | `docker compose logs app \| grep <request_id>` |
| Rebuild after code changes | `docker compose up --build -d --wait` |
| MySQL shell | `docker compose exec mysql mysql -uticketing -pticketing ticketing` |
| Feel Render's free tier | uncomment `cpus: 0.1` in `docker-compose.yml`, set `ADMISSION_ACQUIRE_TIMEOUT=30s` |
| Stop / wipe DB | `docker compose down` / `docker compose down -v` |
| Tests | `./mvnw test` (83 tests; needs Docker for Testcontainers) |

---

## Part 2: A VM or any container platform

The image is self-contained: `docker build -t <registry>/ts-ticketing .`. Give it the variables
above, a health check on `GET /health/ready`, and 512 MB of memory or more.

**One VM with Docker Compose:**
1. A Linux VM with ≥ 1 GB RAM and Docker (`curl -fsSL https://get.docker.com | sudo sh`).
2. `git clone …; cd ts-ticketing-system`, then create `.env` with real secrets
   (`JWT_SECRET`, `ADMIN_SECRET`, `DATABASE_PASSWORD`, `MYSQL_ROOT_PASSWORD`).
3. For a managed MySQL instead of the compose one, set `DATABASE_URL`/`_USERNAME`/`_PASSWORD` in
   `.env`, then remove the `mysql` service and the app's `depends_on`.
4. `docker compose up --build -d --wait`. Put TLS in front of port 8080 (Caddy, nginx, or a cloud
   load balancer) and set `RATE_LIMIT_TRUST_FORWARDED_FOR=true`.
5. Firewall: expose only 443. Bind MySQL to loopback (`MYSQL_HOST_PORT=127.0.0.1:3308`) or remove
   its `ports:` entry.

On a plan with more than 512 MB or more than one CPU, set `JAVA_TOOL_OPTIONS` without
`-XX:MaxRAM=512m -XX:TieredStopAtLevel=1`, and raise `DB_POOL_SIZE` within the database's
connection limit.

---

## Part 3: Render free tier + Aiven MySQL

Render's free plan runs one web service (512 MB, 0.1 CPU, asleep after 15 minutes idle) and has
no managed MySQL, so the database is a free Aiven MySQL. `render.yaml` describes the whole setup.

### 3.1 Push the code
Render deploys from GitHub. Commit and push everything first, including `.mvn/wrapper/`
(the Docker build runs `./mvnw`).

### 3.2 Create the MySQL database (Aiven, free)
1. Sign up at **aiven.io** → **Create service** → **MySQL** → **Free plan**.
2. Choose a cloud region and note it. In step 3.3 you pick the Render region closest to it,
   because every query travels between the two.
3. Wait until the service shows **Running**. Under **Overview → Connection information**, note
   **Host**, **Port**, **User** (`avnadmin`), **Password** and **Database name** (`defaultdb`).
4. Build the JDBC URL (TLS is required):
   ```
   jdbc:mysql://<host>:<port>/defaultdb?sslMode=REQUIRED
   ```
5. Check **Allowed IP addresses** includes `0.0.0.0/0`: Render's outbound IPs aren't fixed on
   the free plan.

Flyway creates the tables on the app's first start. Aiven requires a primary key on every table,
and every table here has one.

### 3.3 Create the web service (Render Blueprint)
1. **render.com** → sign in with GitHub → **New → Blueprint** → select this repository.
   Render reads `render.yaml`.
2. Before applying, check `region` in `render.yaml` (default `frankfurt`). Change it to the region
   closest to Aiven's (`oregon`, `ohio`, `virginia`, `frankfurt`, `singapore`), commit and push.
3. Render asks for the `sync: false` values:
   - `DATABASE_URL` = the JDBC URL from 3.2
   - `DATABASE_USERNAME` = `avnadmin`
   - `DATABASE_PASSWORD` = Aiven's password

   Render generates `JWT_SECRET` and `ADMIN_SECRET`.
4. **Apply**. The first build takes about 5–10 minutes (Maven downloads dependencies, then the
   image records its CDS archive). Startup takes about 100 s more. The deploy turns **Live** once
   `/health/ready` returns 200.

`render.yaml` also sets, sized for the free plan:

| Variable | Value | Why |
|---|---|---|
| `DB_POOL_SIZE` | 5 | Aiven free allows few connections |
| `ADMISSION_MAX_IN_FLIGHT` | 32 | ≈ 6 × pool |
| `ADMISSION_ACQUIRE_TIMEOUT` | 30s | a slow CPU queues instead of shedding (lesson 24) |
| `RATE_LIMIT_TRUST_FORWARDED_FOR` | true | behind Render's proxy, the TCP peer is the proxy |

Without Blueprints: **New → Web Service** → this repo → Runtime **Docker**, Dockerfile path
`./Dockerfile`, Docker context `.`, health check path `/health/ready`, instance type **Free**, and
the environment variables from `render.yaml`.

### 3.4 Verify
```bash
URL=https://<service-name>.onrender.com     # shown on the service page
curl $URL/health/ready                       # {"status":"UP"}; the first call after sleep takes ~2 min
ADMIN_SECRET='<Render → service → Environment → ADMIN_SECRET>' \
  ./burst.sh $URL --requests 2000 --users 300 --hot-users 100 --concurrency 100
```
It passes when the exit code is 0, with `checks: 33/33` and `5xx 0`. Don't run the default
20,000-request burst on the free tier: at 0.1 CPU it takes over 30 minutes. The smaller burst
still covers every check.

### 3.5 Free-tier behaviour
- **Sleeping:** after 15 minutes without traffic the instance stops, and the next request waits
  about 2 minutes. Before a demo, open `$URL/health/ready` and wait for `UP`.
- **Slow under load:** at 0.1 CPU, p99 latency in a burst is about 20 s. Correctness doesn't
  depend on speed.
- **Logs and metrics:** Render → service → **Logs** (JSON lines with `request_id`); `$URL/metrics`.
- **Upgrading:** on Starter (0.5 CPU, no sleep), nothing needs to change. See Part 2 for bigger plans.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| App exits at start with `must be set (env JWT_SECRET)` / `(env ADMIN_SECRET)` | Secret missing from the environment |
| App exits with a Flyway / datasource error in prod | `DATABASE_URL` not set (prod has no fallback) or wrong |
| 401 with a token from another environment | Different `JWT_SECRET` |
| 429 `rate_limited` in a load test | Per-user/IP bucket: spread over more users, or raise `RATE_LIMIT_*` for the test |
| 429 `rate_limited` on `/auth/token` for everyone behind a proxy | `RATE_LIMIT_TRUST_FORWARDED_FOR` is false, so all callers share the proxy's IP bucket |
| 429 `overloaded` | Load shedding: more CPU or DB connections, or accept it |
| Container restarts under load; kernel log shows `Memory cgroup out of memory` | Memory limit too small; see LOAD-TESTING.md §6 before changing JVM flags |
| `Data Dictionary initialization failed` (MySQL) | Interrupted first boot: `docker compose down -v` |
| Render deploy "timed out" / health check failing | Read the logs. Usually `DATABASE_URL` is wrong, or it lacks `?sslMode=REQUIRED` and Aiven refuses the connection |
| Render: `Communications link failure` | Aiven service not Running yet, or its IP allow-list blocks Render |
| Render: burst check "hot-seat losers 409" fails with 429s | `ADMISSION_ACQUIRE_TIMEOUT` isn't 30s, or the burst is larger than 0.1 CPU can handle |
