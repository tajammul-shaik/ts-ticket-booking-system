#!/usr/bin/env python3
"""Burst test for ts-ticketing-system (BRD section 14, "Burst script").

Storms a running instance and proves, from the outside, that it stays correct:

  1. wait for /health/ready (survives a cold start), mint an admin token, create a fresh show
  2. hot-seat storm: N users grab the same seat at the same instant -> exactly one 201
  3. stampede: ~20,000 reserve requests for 1-2 seats, weighted toward a few hot seats,
     while a poller checks invariant_ok on GET /shows/{id} throughout
  4. idempotency: 20 parallel calls with one key -> one reservation; same key + other seat -> 409
  5. per-user limit: one user, 10 parallel requests -> at most `limit` confirmed
  6. spoofing: body user_id is ignored; cross-user cancel -> 404; cancel frees the seat
  7. reconcile: API counts vs what the client saw vs /metrics counters and gauges
  8. print the outcome table; exit 1 if any check failed

Transport errors (timeouts, resets) are counted separately from HTTP statuses (BRD R-10):
a client-side timeout is not a 5xx.

Every request passes the app's edge checks (token validation, request validation, per-caller
rate limiting) on the way in. Rate-limit 429s (rate_limited) are reported apart from admission
control's load shedding (429 overloaded).

Usage:  ./burst.sh [BASE_URL] [options]     (default BASE_URL: $BURST_URL or http://localhost:8080)
        ./burst.sh --help
"""

from __future__ import annotations

import argparse
import asyncio
import collections
import concurrent.futures
import multiprocessing
import os
import random
import re
import resource
import sys
import time
import uuid

import httpx

# --------------------------------------------------------------------------- CLI


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Storm a ts-ticketing-system instance and verify it stayed correct.")
    p.add_argument("base_url", nargs="?", default=os.environ.get("BURST_URL", "http://localhost:8080"),
                   help="the app, e.g. http://localhost:8080 (default: $BURST_URL or http://localhost:8080)")
    p.add_argument("--admin-secret", default=os.environ.get("ADMIN_SECRET", "local-admin-secret"),
                   help="X-Admin-Secret (default: $ADMIN_SECRET, else the compose fallback 'local-admin-secret')")
    p.add_argument("--requests", type=int, default=20_000, help="stampede size (default 20000)")
    p.add_argument("--users", type=int, default=2_000, help="distinct buyers (default 2000)")
    p.add_argument("--hot-users", type=int, default=500, help="buyers in the hot-seat storm (default 500)")
    p.add_argument("--concurrency", type=int, default=1_000,
                   help="max open connections, split across workers (default 1000)")
    p.add_argument("--workers", type=int, default=max(1, min(8, (os.cpu_count() or 2) // 2)),
                   help="client processes for the stampede; one Python process tops out at a few hundred "
                        "req/s (default: half the CPUs, max 8)")
    p.add_argument("--rows", type=int, default=10, help="seat rows A.. (default 10 -> A-J)")
    p.add_argument("--cols", type=int, default=20, help="seats per row (default 20 -> 200 seats)")
    p.add_argument("--limit", type=int, default=4, help="per-user limit of the show (default 4)")
    p.add_argument("--price", type=int, default=25_000, help="price in paise (default 25000)")
    p.add_argument("--timeout", type=float, default=120.0, help="per-request read timeout, seconds (default 120)")
    p.add_argument("--ready-timeout", type=float, default=180.0, help="max wait for /health/ready (default 180)")
    p.add_argument("--seed", type=int, default=None, help="random seed for a reproducible stampede")
    return p.parse_args()


# --------------------------------------------------------------------------- HTTP


class Result:
    """One HTTP exchange: an HTTP status, or a transport error (status None). Picklable."""

    def __init__(self, status, body, headers, error, latency):
        self.status, self.body, self.headers, self.error, self.latency = status, body, headers, error, latency

    @property
    def code(self) -> str | None:
        return self.body.get("error") if isinstance(self.body, dict) else None


# Bounds requests in flight to the number of connections. Without it, thousands of coroutines
# queue inside httpx's connection pool, whose waiter bookkeeping is roughly quadratic in the
# queue length, and the client (not the server) becomes the bottleneck.
IN_FLIGHT: dict[int, asyncio.Semaphore] = {}


async def call(client: httpx.AsyncClient, method: str, path: str, token: str | None = None,
               json=None, headers: dict | None = None) -> Result:
    h = dict(headers or {})
    if token:
        h["Authorization"] = f"Bearer {token}"
    gate = IN_FLIGHT.get(id(client))
    if gate:
        await gate.acquire()
    start = time.perf_counter()  # latency excludes time queued on the client side
    try:
        r = await client.request(method, path, json=json, headers=h)
    except httpx.HTTPError as e:  # timeout, connection reset/refused, protocol error
        return Result(None, None, {}, type(e).__name__, time.perf_counter() - start)
    finally:
        if gate:
            gate.release()
    latency = time.perf_counter() - start
    try:
        body = r.json()
    except ValueError:
        body = r.text
    return Result(r.status_code, body, {k.lower(): v for k, v in r.headers.items()}, None, latency)


def outcome_key(r: Result) -> str:
    if r.status is None:
        return f"transport:{r.error}"
    if r.status < 300:
        return str(r.status)
    return f"{r.status} {r.code or ''}".strip()


def new_client(base_url: str, connections: int, read_timeout: float) -> httpx.AsyncClient:
    limits = httpx.Limits(max_connections=connections, max_keepalive_connections=connections)
    # pool=None: waiting for one of our own connections is not a server timeout.
    timeout = httpx.Timeout(connect=30.0, read=read_timeout, write=30.0, pool=None)
    client = httpx.AsyncClient(base_url=base_url, limits=limits, timeout=timeout)
    IN_FLIGHT[id(client)] = asyncio.Semaphore(connections)
    return client


def _worker(base_url: str, connections: int, read_timeout: float, specs: list[tuple]) -> list[Result]:
    """Runs in a child process: fire every (method, path, token, json) spec, return results in order."""
    async def run():
        async with new_client(base_url, connections, read_timeout) as client:
            return await asyncio.gather(*(call(client, m, p, t, json=j) for m, p, t, j in specs))
    return asyncio.run(run())


class Workers:
    """Spreads a batch of requests over several client processes (results keep their order)."""

    def __init__(self, args):
        self.n = max(1, args.workers)
        self.args = args
        self.pool = concurrent.futures.ProcessPoolExecutor(self.n, mp_context=multiprocessing.get_context("spawn"))

    async def run(self, specs: list[tuple]) -> list[Result]:
        loop = asyncio.get_running_loop()
        per_worker = max(1, self.args.concurrency // self.n)
        chunks = [specs[i::self.n] for i in range(self.n)]
        parts = await asyncio.gather(*(loop.run_in_executor(
            self.pool, _worker, self.args.base_url.rstrip("/"), per_worker, self.args.timeout, chunk)
            for chunk in chunks if chunk))
        results: list[Result] = [None] * len(specs)  # type: ignore[list-item]
        for i, part in enumerate(parts):
            results[i::self.n] = part
        return results

    def close(self):
        self.pool.shutdown()


# --------------------------------------------------------------------------- checks


class Report:
    def __init__(self):
        self.checks: list[tuple[str, bool, str]] = []

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        self.checks.append((name, ok, detail))
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {name}" + (f"  ({detail})" if detail else ""), flush=True)
        return ok

    @property
    def ok(self) -> bool:
        return all(ok for _, ok, _ in self.checks)


def section(title: str):
    print(f"\n== {title}", flush=True)


def parse_metrics(text: str) -> dict[tuple[str, frozenset], float]:
    samples = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        m = re.match(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)', line)
        if not m:
            continue
        labels = frozenset(re.findall(r'(\w+)="([^"]*)"', m.group(3) or ""))
        try:
            samples[(m.group(1), labels)] = float(m.group(4))
        except ValueError:
            pass
    return samples


def metric(samples, name: str, **labels) -> float:
    want = set(labels.items())
    return sum(v for (n, ls), v in samples.items() if n == name and want <= set(ls))


# --------------------------------------------------------------------------- run


async def wait_ready(client: httpx.AsyncClient, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        r = await call(client, "GET", "/health/ready")
        if r.status == 200:
            return True
        await asyncio.sleep(2)
    return False


async def main() -> int:
    args = parse_args()
    rng = random.Random(args.seed)
    run = uuid.uuid4().hex[:8]
    report = Report()

    # Each concurrent connection is a file descriptor.
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    resource.setrlimit(resource.RLIMIT_NOFILE, (min(hard, max(soft, args.concurrency + 512)), hard))

    workers = Workers(args)
    try:
        return await run_burst(args, rng, run, report, workers)
    finally:
        workers.close()


async def run_burst(args, rng, run, report, workers: Workers) -> int:
    base = args.base_url.rstrip("/")
    # `client`: storms that need one shared start gate (hot seat, idempotency, limit).
    # `side`: low-volume calls (setup, invariant poller, metrics) that must not queue behind a storm.
    async with new_client(base, min(args.concurrency, max(args.hot_users, 64)), args.timeout) as client, \
            httpx.AsyncClient(base_url=base, timeout=30.0) as side:

        # ---- 1. setup ------------------------------------------------------------------
        section(f"Setup  (run {run}, target {args.base_url})")
        t0 = time.perf_counter()
        if not report.check("service ready", await wait_ready(side, args.ready_timeout),
                            f"after {time.perf_counter() - t0:.1f}s"):
            return finish(report, {}, 1)

        r = await call(side, "POST", "/auth/token", json={"user_id": f"burst-admin-{run}"},
                       headers={"X-Admin-Secret": args.admin_secret})
        is_admin = r.status == 200 and r.body.get("role") == "admin"
        if not report.check("admin token", is_admin,
                            "" if is_admin else "wrong --admin-secret / $ADMIN_SECRET?" if r.status == 200 else f"HTTP {r.status}"):
            return finish(report, {}, 1)
        admin = r.body["token"]

        rows = [chr(ord("A") + i) for i in range(args.rows)]
        labels = [f"{row}{c}" for row in rows for c in range(1, args.cols + 1)]
        hot_seat = "A12" if "A12" in labels else labels[0]
        check_row = rows[-1]                                          # reserved for steps 4-6
        check_seats = [s for s in labels if s.startswith(check_row) and s[len(check_row):].isdigit()]
        stampede_seats = [s for s in labels if s not in check_seats]
        r = await call(side, "POST", "/shows", admin,
                       json={"name": f"burst-{run}", "seats": labels, "price_paise": args.price,
                             "per_user_limit": args.limit})
        if not report.check("show created", r.status == 201, f"{len(labels)} seats, limit {args.limit}"):
            return finish(report, {}, 1)
        show_id = r.body["id"]
        print(f"  show_id = {show_id}")

        users = [f"burst-{run}-u{i:05d}" for i in range(args.users)]
        t0 = time.perf_counter()
        minted = await workers.run([("POST", "/auth/token", None, {"user_id": u}) for u in users])
        tokens = {u: (r.body or {}).get("token") if r.status == 200 else None for u, r in zip(users, minted)}
        report.check("user tokens minted", all(tokens.values()), f"{len(tokens)} in {time.perf_counter() - t0:.1f}s")

        before = parse_metrics((await side.get("/metrics")).text)
        confirmed_seats: dict[str, list[str]] = {}                  # reservation_id -> seats (client view)
        confirmed_user: dict[str, str] = {}                         # reservation_id -> user
        all_results: list[Result] = []

        def record(res: Result, uid: str):
            all_results.append(res)
            if res.status == 201:
                confirmed_seats[res.body["reservation_id"]] = res.body["seats"]
                confirmed_user[res.body["reservation_id"]] = uid

        async def reserve(uid, seats, key=None, extra=None, gate: asyncio.Event | None = None):
            body = {"seats": seats, "idempotency_key": key or uuid.uuid4().hex}
            if extra:
                body.update(extra)
            if gate:
                await gate.wait()
            res = await call(client, "POST", f"/shows/{show_id}/reserve", tokens[uid], json=body)
            record(res, uid)
            return res

        # ---- 2. hot-seat storm -----------------------------------------------------------
        section(f"Hot-seat storm: {args.hot_users} users -> {hot_seat} at once")
        gate = asyncio.Event()
        tasks = [asyncio.create_task(reserve(u, [hot_seat], gate=gate)) for u in users[:args.hot_users]]
        await asyncio.sleep(0.5)                                      # every task is parked on the gate
        t0 = time.perf_counter()
        gate.set()
        hot = await asyncio.gather(*tasks)
        hot_outcomes = collections.Counter(outcome_key(r) for r in hot)
        print(f"  {dict(hot_outcomes)} in {time.perf_counter() - t0:.2f}s")
        hot_winners = hot_outcomes.get("201", 0)
        report.check("hot seat has exactly one winner", hot_winners == 1, f"{hot_winners} x 201")
        report.check("every loser got 409 seat_taken",
                     hot_outcomes.get("409 seat_taken", 0) == args.hot_users - hot_winners)

        # ---- 3. stampede -----------------------------------------------------------------
        section(f"Stampede: {args.requests} requests, {args.concurrency} connections, {workers.n} client processes")
        hot_set = rng.sample(stampede_seats, min(5, len(stampede_seats)))
        plan = []
        for _ in range(args.requests):
            n = 1 if rng.random() < 0.7 else 2
            chosen = set()
            while len(chosen) < n:
                chosen.add(rng.choice(hot_set) if rng.random() < 0.5 else rng.choice(stampede_seats))
            plan.append((rng.choice(users), sorted(chosen)))

        invariant_samples = []
        stop = asyncio.Event()

        async def poll_invariant():
            while not stop.is_set():
                res = await call(side, "GET", f"/shows/{show_id}", admin)
                if res.status == 200:
                    c = res.body["counts"]
                    invariant_samples.append(res.body["invariant_ok"]
                                             and c["available"] + c["held"] + c["confirmed"] == res.body["total_seats"])
                await asyncio.sleep(0.5)

        poller = asyncio.create_task(poll_invariant())
        t0 = time.perf_counter()
        stampede = await workers.run([("POST", f"/shows/{show_id}/reserve", tokens[u],
                                       {"seats": s, "idempotency_key": uuid.uuid4().hex}) for u, s in plan])
        for (u, _), res in zip(plan, stampede):
            record(res, u)
        elapsed = time.perf_counter() - t0
        stop.set()
        await poller
        st_outcomes = collections.Counter(outcome_key(r) for r in stampede)
        lat = sorted(r.latency for r in stampede if r.status is not None)
        print(f"  {elapsed:.1f}s, {len(stampede) / elapsed:.0f} req/s")
        for k, v in sorted(st_outcomes.items(), key=lambda kv: -kv[1]):
            print(f"    {k:<28} {v:>7}")
        if lat:
            print(f"  latency p50 {pct(lat, 50):.0f} ms, p95 {pct(lat, 95):.0f} ms, "
                  f"p99 {pct(lat, 99):.0f} ms, max {lat[-1] * 1000:.0f} ms (client-side, from send to response)")
        report.check("invariant held during the stampede", bool(invariant_samples) and all(invariant_samples),
                     f"{len(invariant_samples)} samples")

        # ---- 4. idempotency --------------------------------------------------------------
        section("Idempotency: 20 parallel calls, one key")
        uid = f"burst-{run}-idem"
        tokens[uid] = (await call(side, "POST", "/auth/token", json={"user_id": uid})).body["token"]
        seat_a, seat_b = check_seats[0], check_seats[1]
        key = f"idem-{run}"
        gate = asyncio.Event()
        tasks = [asyncio.create_task(reserve(uid, [seat_a], key=key, gate=gate)) for _ in range(20)]
        await asyncio.sleep(0.2)
        gate.set()
        idem = await asyncio.gather(*tasks)
        ids = {r.body.get("reservation_id") for r in idem if r.status in (200, 201)}
        idem_outcomes = collections.Counter(outcome_key(r) for r in idem)
        print(f"  {dict(idem_outcomes)}")
        report.check("one reservation id across all 20 responses",
                     len(ids) == 1 and idem_outcomes.get("201", 0) == 1 and idem_outcomes.get("200", 0) == 19,
                     f"{len(ids)} id(s)")
        replays = [r for r in idem if r.status == 200]
        report.check("replays carry Idempotent-Replay: true",
                     all(r.headers.get("idempotent-replay") == "true" for r in replays))
        r = await reserve(uid, [seat_b], key=key)
        report.check("same key, different seat -> 409 idempotency_key_conflict",
                     r.status == 409 and r.code == "idempotency_key_conflict", outcome_key(r))

        # ---- 5. per-user limit -----------------------------------------------------------
        section(f"Per-user limit: one user, 10 parallel requests, limit {args.limit}")
        uid = f"burst-{run}-limit"
        tokens[uid] = (await call(side, "POST", "/auth/token", json={"user_id": uid})).body["token"]
        targets = check_seats[2:12]
        gate = asyncio.Event()
        tasks = [asyncio.create_task(reserve(uid, [s], gate=gate)) for s in targets]
        await asyncio.sleep(0.2)
        gate.set()
        lim = await asyncio.gather(*tasks)
        lim_outcomes = collections.Counter(outcome_key(r) for r in lim)
        print(f"  {dict(lim_outcomes)}")
        report.check(f"at most {args.limit} confirmed", lim_outcomes.get("201", 0) <= args.limit,
                     f"{lim_outcomes.get('201', 0)} confirmed")
        report.check("the rest got 409 per_user_limit",
                     lim_outcomes.get("409 per_user_limit", 0) == len(targets) - lim_outcomes.get("201", 0))

        # ---- 6. spoofing and cancel ------------------------------------------------------
        section("Spoofing and cancel")
        attacker, other = f"burst-{run}-spoofer", f"burst-{run}-other"
        for u in (attacker, other):
            tokens[u] = (await call(side, "POST", "/auth/token", json={"user_id": u})).body["token"]
        seat_s = check_seats[12]
        r = await reserve(attacker, [seat_s], extra={"user_id": "victim"})
        report.check("body user_id is ignored (reservation owned by token user)",
                     r.status == 201 and r.body.get("user_id") == attacker, f"user_id={r.body.get('user_id') if isinstance(r.body, dict) else r.body}")
        spoof_id = r.body.get("reservation_id") if r.status == 201 else None
        cancelled_ids = set()
        if spoof_id:
            x = await call(side, "POST", f"/reservations/{spoof_id}/cancel", tokens[other])
            report.check("cross-user cancel -> 404 reservation_not_found",
                         x.status == 404 and x.code == "reservation_not_found", outcome_key(x))
            x = await call(side, "POST", f"/reservations/{spoof_id}/cancel", tokens[attacker])
            report.check("owner cancel -> 200 cancelled", x.status == 200 and x.body.get("status") == "cancelled")
            if x.status == 200:
                cancelled_ids.add(spoof_id)
            r = await reserve(other, [seat_s])
            report.check("cancelled seat is re-bookable at once", r.status == 201, outcome_key(r))
            x = await call(side, "POST", f"/reservations/{spoof_id}/cancel", tokens[attacker])
            s = await call(side, "GET", f"/shows/{show_id}", admin)
            seat_state = next((z["status"] for z in s.body["seats"] if z["label"] == seat_s), None)
            report.check("repeat cancel is idempotent and doesn't resurrect the re-sold seat",
                         x.status == 200 and seat_state == "confirmed", f"seat {seat_s} is {seat_state}")

        # ---- 7. reconcile ----------------------------------------------------------------
        section("Reconcile: API vs client vs /metrics")
        s = await call(side, "GET", f"/shows/{show_id}", admin)
        counts = s.body["counts"]
        report.check("invariant_ok after the burst", s.body["invariant_ok"]
                     and counts["available"] + counts["held"] + counts["confirmed"] == s.body["total_seats"],
                     f"available {counts['available']} + held {counts['held']} + confirmed {counts['confirmed']}"
                     f" = {s.body['total_seats']}")

        live = {rid: seats for rid, seats in confirmed_seats.items() if rid not in cancelled_ids}
        sold = [seat for seats in live.values() for seat in seats]
        dupes = [seat for seat, n in collections.Counter(sold).items() if n > 1]
        report.check("no seat sold twice (client view)", not dupes, f"{len(dupes)} double-sold" if dupes else
                     f"{len(sold)} seats in {len(live)} live reservations")
        per_user = collections.Counter()
        for rid, seats in live.items():
            per_user[confirmed_user[rid]] += len(seats)
        over = {u: n for u, n in per_user.items() if n > args.limit}
        report.check("no user above the per-user limit", not over, f"max {max(per_user.values(), default=0)} seats/user")

        transport = sum(1 for r in all_results if r.status is None)
        server_errors = sum(1 for r in all_results if r.status is not None and r.status >= 500)
        if transport == 0:
            report.check("API confirmed count == seats in client-seen 201s", counts["confirmed"] == len(sold),
                         f"{counts['confirmed']} vs {len(sold)}")
        else:
            print(f"  (skipped exact client/API match: {transport} transport errors hide some outcomes)")

        await asyncio.sleep(1.0)                                     # let the gauge snapshot age out
        after = parse_metrics((await side.get("/metrics")).text)
        m_confirmed = metric(after, "reservations_confirmed_total", show_id=show_id)
        m_cancelled = metric(after, "reservations_cancelled_total", show_id=show_id)
        n_201 = len(confirmed_seats)
        if transport == 0:
            report.check("reservations_confirmed_total == 201 responses", m_confirmed == n_201,
                         f"{m_confirmed:.0f} vs {n_201}")
        report.check("reservations_cancelled_total == cancels", m_cancelled == len(cancelled_ids),
                     f"{m_cancelled:.0f} vs {len(cancelled_ids)}")
        for status in ("available", "held", "confirmed"):
            g = metric(after, f"seats_{status}", show_id=show_id)
            report.check(f"seats_{status} gauge == API count", g == counts[status], f"{g:.0f} vs {counts[status]}")
        observed = collections.Counter()
        for r in all_results:
            if r.status == 200:
                observed["idempotent_replay"] += 1
            elif r.status in (409, 429) and r.code:
                observed[r.code] += 1
        for reason in ("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_key_conflict", "overloaded"):
            delta = metric(after, "reservations_declined_total", reason=reason) - \
                metric(before, "reservations_declined_total", reason=reason)
            if transport == 0:
                report.check(f"declined{{reason={reason}}} delta == observed", delta == observed[reason],
                             f"{delta:.0f} vs {observed[reason]}")
        # Readiness 503 during a cold start is by design (fail closed), so it is not counted.
        five_xx = sum(v for (n, ls), v in after.items() if n == "http_server_requests_seconds_count"
                      and any(k == "status" and v2.startswith("5") for k, v2 in ls)
                      and ("uri", "/health/ready") not in ls)
        report.check("server-side http_server_requests: zero 5xx since start", five_xx == 0, f"{five_xx:.0f}")

        # ---- 8. summary ------------------------------------------------------------------
        totals = collections.Counter(outcome_key(r) for r in all_results)
        report.check("zero 5xx responses in the burst", server_errors == 0, f"{server_errors}")
        summary = {
            "show_id": show_id,
            "requests": len(all_results),
            "confirmed (201)": totals.get("201", 0),
            "replayed (200)": totals.get("200", 0),
            "declined seat_taken": totals.get("409 seat_taken", 0),
            "declined per_user_limit": totals.get("409 per_user_limit", 0),
            "declined key_conflict": totals.get("409 idempotency_key_conflict", 0),
            "shed 429 overloaded": totals.get("429 overloaded", 0),
            "429 rate_limited": totals.get("429 rate_limited", 0),
            "5xx": server_errors,
            "transport errors (not 5xx)": transport,
            "hot-seat winners": hot_winners,
            "final seats": f"available {counts['available']}, held {counts['held']}, confirmed {counts['confirmed']}",
        }
        other_codes = {k: v for k, v in totals.items() if k not in {
            "201", "200", "409 seat_taken", "409 per_user_limit", "409 idempotency_key_conflict", "429 overloaded",
            "429 rate_limited"}}
        if other_codes:
            summary["other outcomes"] = dict(other_codes)
        return finish(report, summary, 0)


def pct(sorted_latencies: list[float], p: int) -> float:
    return sorted_latencies[min(len(sorted_latencies) - 1, int(len(sorted_latencies) * p / 100))] * 1000


def finish(report: Report, summary: dict, _code: int) -> int:
    section("Outcome")
    width = max((len(k) for k in summary), default=0)
    for k, v in summary.items():
        print(f"  {k:<{width}}  {v}")
    failed = [name for name, ok, _ in report.checks if not ok]
    print(f"\n  checks: {len(report.checks) - len(failed)}/{len(report.checks)} passed")
    for name in failed:
        print(f"  FAILED: {name}")
    print("\nRESULT:", "PASS" if not failed else "FAIL")
    return 0 if not failed else 1


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(130)
