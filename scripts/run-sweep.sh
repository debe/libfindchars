#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

FORKS=1
WARMUP=3
MEASUREMENT=5
SKIP_BUILD=false
PERFNORM=""
JSON_OUT="docs/sweep-data/sweep-results.json"

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --quick        Smoke test: 1 fork, 1 warmup, 1 measurement"
    echo "  --skip-build   Skip Maven build"
    echo "  --forks N      Number of forks (default: 1)"
    echo "  --perfnorm     Enable hardware counter profiling (requires Linux perf_events)"
    echo "  -h, --help     Show this help"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --quick)    FORKS=1; WARMUP=1; MEASUREMENT=1; shift ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --forks)    FORKS="$2"; shift 2 ;;
        --perfnorm) PERFNORM="-prof perfnorm"; shift ;;
        -h|--help)  usage ;;
        *)          echo "Unknown option: $1"; exit 1 ;;
    esac
done

echo "=== Sweep Benchmark ==="
echo "  forks=$FORKS, warmup=$WARMUP, measurement=$MEASUREMENT"
echo "  perfnorm=${PERFNORM:-disabled}"

# Build
if [ "$SKIP_BUILD" = false ]; then
    echo ""
    echo "--- Building ---"
    ( cd "$ROOT_DIR/java" && ./mvnw clean package -DskipTests -pl libfindchars-bench -am -q )
fi

# Find the bench jar
BENCH_JAR=$(ls java/libfindchars-bench/target/libfindchars-bench-*.jar | head -1)
echo ""
echo "--- Running JMH ($BENCH_JAR) ---"

mkdir -p docs/sweep-data

java -jar "$BENCH_JAR" \
    -f "$FORKS" -wi "$WARMUP" -i "$MEASUREMENT" \
    -rf json -rff "$JSON_OUT" \
    $PERFNORM \
    SweepBenchmark

echo ""
echo "--- Parsing results ---"
python3 scripts/parse-sweep.py "$JSON_OUT" docs/sweep-data

# Fit cost model if numpy is available (must run before cost-model gnuplot scripts)
if python3 -c "import numpy" 2>/dev/null; then
    echo ""
    echo "--- Fitting cost model ---"
    python3 scripts/fit-cost-model.py docs/sweep-data
else
    echo ""
    echo "numpy not found — skipping cost model fit"
fi

# Generate plots if gnuplot is available
if command -v gnuplot &>/dev/null; then
    echo ""
    echo "--- Generating plots ---"
    gnuplot java/libfindchars-bench/sweep-overview.gnuplot && echo "  wrote docs/sweep-overview.png"
    gnuplot java/libfindchars-bench/sweep-instructions.gnuplot 2>/dev/null && echo "  wrote docs/sweep-instructions.png" || echo "  skipped sweep-instructions.png (no perfnorm data)"
    gnuplot java/libfindchars-bench/sweep-cost-model.gnuplot 2>/dev/null && echo "  wrote docs/sweep-cost-model.png" || echo "  skipped sweep-cost-model.png (no perfnorm data)"
    gnuplot java/libfindchars-bench/sweep-cost-model-combined.gnuplot 2>/dev/null && echo "  wrote docs/sweep-cost-model-combined.png" || echo "  skipped sweep-cost-model-combined.png (no perfnorm data)"
else
    echo ""
    echo "gnuplot not found — skipping plot generation"
fi

echo ""
echo "=== Done ==="
