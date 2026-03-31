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
JSON_OUT="docs/csv-sweep-data/csv-sweep-results.json"

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

echo "=== CSV Sweep Benchmark ==="
echo "  forks=$FORKS, warmup=$WARMUP, measurement=$MEASUREMENT"
echo "  perfnorm=${PERFNORM:-disabled}"

# Build
if [ "$SKIP_BUILD" = false ]; then
    echo ""
    echo "--- Building ---"
    mvn clean package -DskipTests -pl libfindchars-bench -am -q
fi

# Find the bench jar
BENCH_JAR=$(ls libfindchars-bench/target/libfindchars-bench-*.jar | head -1)
echo ""
echo "--- Running JMH ($BENCH_JAR) ---"

mkdir -p docs/csv-sweep-data

java -jar "$BENCH_JAR" \
    -f "$FORKS" -wi "$WARMUP" -i "$MEASUREMENT" \
    -rf json -rff "$JSON_OUT" \
    $PERFNORM \
    CsvSweepBenchmark

echo ""
echo "--- Parsing results ---"
python3 scripts/parse-csv-sweep.py "$JSON_OUT" docs/csv-sweep-data

# Generate plots if gnuplot is available
if command -v gnuplot &>/dev/null; then
    echo ""
    echo "--- Generating plots ---"
    gnuplot libfindchars-bench/csv-sweep-overview.gnuplot && echo "  wrote docs/csv-sweep-overview.png"
    gnuplot libfindchars-bench/csv-sweep-instructions.gnuplot 2>/dev/null && echo "  wrote docs/csv-sweep-instructions.png" || echo "  skipped csv-sweep-instructions.png (no perfnorm data)"
else
    echo ""
    echo "gnuplot not found — skipping plot generation"
fi

echo ""
echo "=== Done ==="
