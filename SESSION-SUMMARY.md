# Session summary

## 2026-10-11: finished the switch to one application

- **Docs:** LOAD-TESTING.md §8–10 still listed microservices commands (`booking-service`
  container, `/actuator/prometheus`, `gateway_rate_limited`) and `GATEWAY_*` variables; they
  now match the single app (`docker compose exec app`, `requests_rate_limited` on `/metrics`,
  `RATE_LIMIT_TRUST_FORWARDED_FOR`, the Dockerfile's `JAVA_TOOL_OPTIONS`).
- **Verified:** `./mvnw test` 83/83.
- **Committed** everything since `def2e08`, including `.mvn/wrapper/` (CLAUDE.md lesson 6).
- **Still to do (needs your accounts):** create the Aiven MySQL, then the Render Blueprint
  (DEPLOYMENT.md Part 3); burst the live URL; review WRITEUP.md's AI-usage section.

## 2026-10-08 to 10: machine checks, deployment options, monolith build for Render

What was asked and answered or built in this session, in order.

### 1. Java versions on this machine
- **Installed:**
  - Java 11 is the shell default: `~/.zshrc` lines 109–110 set
    `JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64` and put it first on `PATH`.
  - Java 21 is runtime only (`openjdk-21-jre-headless`, no `javac`).
  - JDK 25 has a compiler and is what builds this project (`--release 21`).
  - Corretto 17 JDKs are in `~/.jdks` (downloaded by IntelliJ).
- **To make Java 21 the default:** change line 109 to `/usr/lib/jvm/java-21-openjdk-amd64` and
  run `source ~/.zshrc`. Maven builds need a JDK (`sudo apt install openjdk-21-jdk`), or keep
  JDK 25 for them. No change was made.
- **List every install with its path, version and whether it is a full JDK:**
  ```bash
  for d in /usr/lib/jvm/*/ ~/.jdks/*/; do d=${d%/}; [ -L "$d" ] && continue; [ -x "$d/bin/java" ] || continue; printf '%-45s %-40s %s\n' "$d" "$("$d/bin/java" -version 2>&1 | head -1)" "$([ -x "$d/bin/javac" ] && echo JDK || echo 'JRE only')"; done; echo "active: $(readlink -f "$(command -v java)")"; echo "JAVA_HOME=$JAVA_HOME"
  ```
  Also useful: `update-alternatives --list java` (apt installs only), `readlink -f $(which java)`,
  `sudo update-alternatives --config java`.

### 2. RAM (MSI GF63 Thin 10SCXR)
- **At the time:** 8 GB installed (7.0 GiB visible), about 1.9 GiB available, swap 3.3 of 4 GiB
  in use. Too tight for the full compose stack (~2.5 GB under load) next to an IDE.
- **Slots** (`sudo dmidecode -t memory`): 2.
  - ChannelA: 8 GB DDR4-3200 Samsung `M471A1K43DB1-CWE`.
  - ChannelB: **empty**.
  - The firmware reports a **32 GB** maximum.
- **Upgrade options:**
  - Add the same 8 GB stick → 16 GB, dual-channel.
  - Recommended: 2 × 16 GB DDR4-3200 SO-DIMM → 32 GB, the firmware maximum.
  - 8 + 16 = 24 GB works, but only partly dual-channel.

### 3. How to deploy the microservices (one VM with Docker Compose)
1. Commit and push. GitHub `origin` still has only `def2e08`.
2. Create a VM: Ubuntu 24.04, ≥ 4 GB RAM. Open ports 22 and 8080 (on Oracle, also open them in
   iptables).
3. Install Docker: `curl -fsSL https://get.docker.com | sudo sh`.
4. Clone, then `cp .env.example .env` with real secrets (`openssl rand`).
5. Bind the internal ports to loopback:
   `CONFIG_HOST_PORT=127.0.0.1:8888`, `DISCOVERY_HOST_PORT=127.0.0.1:8761`,
   `MYSQL_HOST_PORT=127.0.0.1:3308`.
6. `docker compose up --build -d --wait`, then from the laptop run
   `ADMIN_SECRET=… ./burst.sh http://<VM_IP>:8080`.
7. Optional HTTPS: Caddy with a `<ip-dashes>.sslip.io` hostname, plus
   `RATE_LIMIT_TRUST_FORWARDED_FOR=true`.

The full reference is in DEPLOYMENT.md Parts 1–2.

### 4. Hosting options other than Oracle Cloud
These figures were quoted from memory; check current pricing before signing up.
- **Free credits:**
  - Google Cloud: about $300 for 90 days; `e2-standard-2` (8 GB) fits.
  - DigitalOcean: about $200 for 60 days.
  - Azure: about $200 for 30 days.
  - Linode: about $100 for 60 days.
  - AWS: new-account credits.
  - GitHub Student Pack.
- **Cheap paid:** Hetzner CX32/CX33 (4 vCPU / 8 GB, about €7 a month, billed hourly), Contabo,
  Vultr, AWS Lightsail.
- **Poor fit for the microservices:** Render, Railway and Fly.io (six billable pieces), and the
  laptop plus a Cloudflare Tunnel (too little free RAM).
- **Picks:** GCP trial (free) or Hetzner (least hassle).

### 5. Monolith on Render? Yes, and built it (free tier + Aiven MySQL)
- **Why it fits:** booking-service already validates JWTs, sheds load and sets request ids, so a
  monolith is booking-service plus the token endpoint, minus the Spring Cloud clients.
- **The database:** Render has no managed MySQL. Aiven's free MySQL was chosen. Postgres would
  mean rewriting MySQL-specific SQL, and TiDB/PlanetScale lock differently.

**Built**

| File | What |
|---|---|
| `monolith-app/pom.xml` | Depends on booking-service and identity-service `lib` jars (Spring Cloud, retry and AOP excluded) and packages `config-repo/` as resources |
| `MonolithApplication` | Scans `com.ts.ticketing.booking` (minus its main class); imports `AuthController` and `IdentityProperties` |
| `TokenEndpointSecurity` | `@Order(1)` filter chain that permits only `/auth/token`; booking's `SecurityConfig` handles everything else |
| `application.yml`, `application-prod.yml`, `config-repo/monolith-app.yml` | Import the same config-repo files; the monolith overrides only `server.port: ${PORT:8080}` |
| `monolith-app/Dockerfile` | No BuildKit cache mounts; Class Data Sharing archive recorded at build time; `MaxRAM=512m`, heap 60 %, C1-only JIT |
| `MonolithFlowTest` | 4 wiring tests on real MySQL |
| `booking-service/pom.xml`, `identity-service/pom.xml` | Also build a plain `lib` classifier jar (without `application.yml`) |
| root `pom.xml`, root `Dockerfile` | New module registered and copied into the build |
| `render.yaml` | Blueprint: free plan, `healthCheckPath /health/ready`, generated `JWT_SECRET`/`ADMIN_SECRET`, prompted `DATABASE_*`, `DB_POOL_SIZE=5`, `ADMISSION_MAX_IN_FLIGHT=32`, `ADMISSION_ACQUIRE_TIMEOUT=30s` |
| `docker-compose.monolith.yml` | Monolith + its own MySQL, port 8090, 512 MB, optional `cpus: 0.1` |

**Verified** (details in LOAD-TESTING.md §11; CLAUDE.md lessons 23–25)

| Check | Result |
|---|---|
| `./mvnw test` | **87 pass** (83 + 4). One surefire "kill self fork JVM" warning appeared once and could not be reproduced per module |
| Startup at 0.1 CPU / 512 MB, fresh DB | 309 s plain → **99 s** with CDS + C1 |
| Burst 2,000 requests at 0.1 CPU, admission wait 5 s | 32/33: some hot-seat losers got 429 instead of 409 |
| Same burst with admission wait 30 s | **33/33**, 0 × 429, 0 5xx, p99 22.9 s, 240 MiB |
| Default 20k burst, full CPU, 512 MB | **33/33**, p99 6.7 s, `memory.peak` 434 MiB, 0 restarts |
| `docker-compose.monolith.yml` | Healthy; burst 33/33 |
| Microservices after the changes | Images rebuild; burst 33/33 |

**Docs updated:**
- DEPLOYMENT.md Part 3: Aiven + Render step by step, free-tier behaviour, troubleshooting.
- README: "Run as a monolith".
- ARCHITECTURE (modules, status), CODE-EXPLANATION §8.4, WRITEUP §1 and §13.
- LOAD-TESTING §11, CLAUDE.md (commands, ports, layout, lessons 23–25), test counts 83 → 87.

**Leftover:** a `monolith` test database in the local compose MySQL volume (removed by
`docker compose down -v`).

### 6. Still to do
1. Commit and push. Nothing has been committed since `def2e08`, and Render builds from GitHub.
2. Create the Aiven free MySQL and build
   `DATABASE_URL=jdbc:mysql://<host>:<port>/defaultdb?sslMode=REQUIRED` (user `avnadmin`).
3. Set `region` in `render.yaml` to the region nearest Aiven's, then Render → New → Blueprint →
   this repo → paste the DB values.
4. Run the burst against the live URL with `--requests 2000 --users 300 --hot-users 100
   --concurrency 100` and save the output.
5. Review WRITEUP.md's AI-usage section.

## 2026-10-06/07: split into microservices

The single Spring Boot service was restructured into a Maven multi-module microservices project
that runs from a clean clone with `docker compose up --build -d --wait`.

| Module | Role |
|---|---|
| `config-server` | Spring Cloud Config Server, **native** profile; all configuration in `config-repo/*.yml`, secrets only from env |
| `discovery-service` | Eureka service registry (dashboard :8761) |
| `gateway-service` | Public entry (:8080): request id, request validation, JWT validation, per-user/IP rate limiting, routing via Eureka, JSON errors |
| `identity-service` | `POST /auth/token` (HS256 JWT, admin via secret) |
| `booking-service` | Shows, reserve, cancel, seat state, metrics, MySQL (logic unchanged) |
| `common` | Shared JWT, error body, request-id filter, JSON logging |

**Verified**
- 83 tests pass across all modules (booking's 53 on real MySQL); 87 after the monolith was added.
- Burst through the gateway: 20,533 requests, **33/33 checks, 0 5xx, 0 transport errors, 0 restarts**.
- Clean-clone run with no `.env`: all containers healthy, burst passes.

**Found and fixed by load testing the split** (details: LOAD-TESTING.md §6, CLAUDE.md lessons 15–22)
- identity OOM-killed at 320 MB → 512 MB + JVM caps.
- booking OOM-killed (bigger non-heap as a microservice) → gateway caps connections per service at
  500 and queues the rest; booking heap 50 %.
- gateway OOM-killed at 4,000 connections (Netty direct memory) → direct memory capped.
- Duplicate `X-Request-Id` header → fixed.
- Stress limit documented: 4,000 concurrent connections → no crash, ~14 % gateway 504s.

**Docs**: README (run/execute), CODE-EXPLANATION (every module and file), WRITEUP (precise
pointers), ARCHITECTURE, LOAD-TESTING, DEPLOYMENT, CLAUDE all updated. `README.md` is no longer
git-ignored. The old `env/` templates were replaced by `.env.example`.

**Still to do**: commit (nothing committed since `def2e08`), deploy and burst a public gateway URL,
review WRITEUP's AI-usage section.

## 2026-10-05: single service built and load-tested

Built BRD steps 2 and 4–7 (Flyway schema, shows, reserve transaction, cancel, request ids, JSON
logs, metrics, admission control), the burst script and 66 tests; load-tested and tuned memory
for 512 MB (LOAD-TESTING.md §4–5). Changes worth knowing from that phase:

1. Decline-only fast path before the reserve transaction (never grants; 129 → 490 req/s).
2. Heap below the BRD's 75 % (measured OOM risk).
3. Bounded Tomcat connections (~110 KB of heap per request in flight).
4. Exhausted lock retries → 429 `overloaded` instead of 409.

## 2026-10-10: microservices → one application

- **Asked:** remove the microservices architecture; build one application that meets the BRD,
  using best practices for high concurrency; pass all tests; deploy on Render with Aiven MySQL.
- **Backup first:** nothing past `def2e08` was committed, so the whole working tree was saved to
  `../ts-ticket-booking-system-microservices-backup-2026-10-10.tar.gz` before any deletion.
- **Removed:** `common`, `config-server`, `discovery-service`, `gateway-service`,
  `identity-service`, `booking-service`, `monolith-app`, `docker-compose.monolith.yml`.
- **Now:** one Maven module, `com.ts.ticketing` (`auth`, `security`, `web`, `api`, `show`,
  `reservation`, `observability`, `health`, `config`). The gateway's request validation and
  per-user rate limiting became servlet filters (`web/FilterConfig` sets the order); config-repo
  became `application.yml` + `application-prod.yml`. New: Connector/J round-trip options and
  Hikari keepalive for a remote Aiven database.
- **Verified:** `./mvnw test` 83/83; `./burst.sh` 33/33 at 596 req/s, 0 5xx, 412 MiB of 512;
  Render-free simulation (0.1 CPU) ready in 93 s, 2,000-request burst 33/33 (LOAD-TESTING.md §12).
- **Still to do:** commit (including `.mvn/wrapper/`), push, create the Aiven and Render services
  (DEPLOYMENT.md Part 3).
