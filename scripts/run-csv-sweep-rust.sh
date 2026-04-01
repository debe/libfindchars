#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUST_DIR="$ROOT_DIR/rust"

# --- Defaults ---
QUICK=false
EXTRA_ARGS=""

# --- Parse args ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --quick) QUICK=true; shift ;;
        *) EXTRA_ARGS="$EXTRA_ARGS $1"; shift ;;
    esac
done

echo "=== Rust findchars-csv sweep benchmark ==="
echo "  quick=${QUICK}"

cd "$RUST_DIR"

if [ "$QUICK" = true ]; then
    echo ""
    echo "--- Quick run (short warm-up/measurement) ---"
    cargo bench -p findchars-bench --bench csv_sweep -- --warm-up-time 1 --measurement-time 2 $EXTRA_ARGS
else
    echo ""
    echo "--- Full run ---"
    cargo bench -p findchars-bench --bench csv_sweep -- $EXTRA_ARGS
fi

echo ""
echo "--- Results in target/criterion/ ---"
