#!/usr/bin/env bash
# One-command burst test (BRD section 14).
#
#   ./burst.sh                               the local stack, http://localhost:8080
#   ./burst.sh <BASE_URL> [options]          e.g.  ./burst.sh http://localhost:8080 --requests 5000
#   ADMIN_SECRET=... ./burst.sh https://<app-host>
#   ./burst.sh --help
#
# Runs burst.py with httpx from, in order of preference:
#   1. .burst-venv/ (git-ignored), created on first run with python3 -m venv + pip
#   2. a python:3.13-slim container, if the local Python can't build a venv (no ensurepip/pip)
# Exit code 0 = every correctness check passed, 1 = at least one failed.
set -euo pipefail
cd "$(dirname "$0")"

# The base URL is the first argument; default it here so every path below sees it.
if [[ $# -eq 0 || "$1" == -* ]]; then
  set -- "${BURST_URL:-http://localhost:8080}" "$@"
fi

VENV=.burst-venv
if "$VENV/bin/python" -c 'import httpx' 2>/dev/null; then
  exec "$VENV/bin/python" burst.py "$@"
fi

if command -v python3 >/dev/null && python3 -m venv "$VENV" >/dev/null 2>&1 \
    && "$VENV/bin/pip" install --quiet --disable-pip-version-check 'httpx>=0.27,<1'; then
  exec "$VENV/bin/python" burst.py "$@"
fi
rm -rf "$VENV"

echo "No usable local python3 venv; running burst.py in a python:3.13-slim container." >&2
args=("$@")
network=(--network host)
if [[ "$(uname -s)" != "Linux" ]]; then
  # Docker Desktop: the container's localhost is not the host's.
  network=()
  for i in "${!args[@]}"; do
    args[$i]="${args[$i]//localhost/host.docker.internal}"
    args[$i]="${args[$i]//127.0.0.1/host.docker.internal}"
  done
fi
tty=(); [[ -t 1 ]] && tty=(-t)
# ${a[@]+"${a[@]}"}: empty arrays are safe under `set -u` even in macOS's bash 3.2.
exec docker run --rm -i ${tty[@]+"${tty[@]}"} ${network[@]+"${network[@]}"} --ulimit nofile=65536:65536 \
  -e ADMIN_SECRET -e BURST_URL -e PIP_ROOT_USER_ACTION=ignore -e PIP_DISABLE_PIP_VERSION_CHECK=1 \
  -v "$PWD/burst.py:/burst.py:ro" python:3.13-slim \
  sh -c 'pip install --quiet "httpx>=0.27,<1" && exec python -u /burst.py "$@"' burst ${args[@]+"${args[@]}"}
