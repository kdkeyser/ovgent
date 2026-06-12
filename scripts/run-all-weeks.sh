#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Download data and build the Rust binary if needed.
bash scripts/setup.sh

./gradlew pipeline:run --args="--weeks 2026-W23,2026-W24,2026-W25,2026-W26,2026-W27,2026-W28,2026-W29,2026-W30,2026-W31,2026-W32,2026-W33,2026-W34,2026-W35,2026-W36,2026-W37,2026-W38,2026-W39,2026-W40,2026-W41,2026-W42,2026-W43,2026-W44,2026-W45,2026-W46,2026-W47,2026-W48,2026-W49,2026-W50,2026-W51,2026-W52"

echo "=== All weeks complete ==="
