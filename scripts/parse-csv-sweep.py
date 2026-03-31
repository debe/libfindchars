#!/usr/bin/env python3
"""Parse JMH JSON output into per-sweep TSV files for CSV benchmark gnuplot."""

import json
import os
import sys


DATA_SIZE_BYTES = 100 * 1024 * 1024  # 100 MB

# Baseline values
BASELINE = {"columns": 10, "quote": 5, "fieldlen": 16}

# Sweep definitions: (name, param_index, values, description)
SWEEPS = [
    ("columns",  0, [5, 10, 25, 50, 100], "columns"),
    ("quotes",   1, [0, 5, 25, 50],       "quotePercent"),
    ("fieldlen", 2, [10, 16, 25, 50],     "avgFieldLen"),
]

# Perfnorm counter keys in JMH JSON
PERFNORM_KEYS = [
    "instructions", "cycles", "branches", "branch-misses", "L1-dcache-load-misses"
]


def parse_config(config_str):
    """Parse 'columns-quotePercent-avgFieldLen' into tuple of ints."""
    return tuple(int(x) for x in config_str.split("-"))


def method_name(benchmark_name):
    """Extract method name from fully qualified benchmark name."""
    return benchmark_name.rsplit(".", 1)[-1]


def main():
    if len(sys.argv) < 2:
        print("Usage: parse-csv-sweep.py <jmh-results.json> [output-dir]", file=sys.stderr)
        sys.exit(1)

    json_path = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else "docs/csv-sweep-data"
    os.makedirs(output_dir, exist_ok=True)

    with open(json_path) as f:
        results = json.load(f)

    # Index results by (config_tuple, method)
    indexed = {}
    for r in results:
        params = r.get("params", {})
        config_str = params.get("config", "")
        if not config_str:
            continue
        config = parse_config(config_str)
        method = method_name(r["benchmark"])

        ops_s = float(r["primaryMetric"]["score"])
        ops_s_err = r["primaryMetric"]["scoreError"]
        ops_s_err = float(ops_s_err) if ops_s_err != "NaN" else 0.0

        # Extract perfnorm counters from secondary metrics
        counters = {}
        secondary = r.get("secondaryMetrics", {})
        for key in PERFNORM_KEYS:
            metric_key = key
            if metric_key in secondary:
                counters[key] = secondary[metric_key]["score"]
            else:
                # Try with perfnorm prefix
                for sk in secondary:
                    if key in sk:
                        counters[key] = secondary[sk]["score"]
                        break

        indexed[(config, method)] = {
            "ops_s": ops_s,
            "ops_s_err": ops_s_err,
            "counters": counters,
        }

    # Generate per-sweep TSV
    for sweep_name, param_idx, values, _ in SWEEPS:
        tsv_path = os.path.join(output_dir, f"csv-sweep-{sweep_name}.tsv")
        with open(tsv_path, "w") as out:
            # Header
            cols = ["param", "method", "ops_s", "ops_s_err", "gb_s"]
            cols += [k.replace("-", "_") for k in PERFNORM_KEYS]
            out.write("\t".join(cols) + "\n")

            for val in values:
                # Build config tuple for this sweep point
                baseline_list = [BASELINE["columns"], BASELINE["quote"],
                                 BASELINE["fieldlen"]]
                baseline_list[param_idx] = val
                config = tuple(baseline_list)

                for method in ["simdParse", "fastCsv"]:
                    key = (config, method)
                    if key not in indexed:
                        print(f"  WARNING: missing {config} / {method}", file=sys.stderr)
                        continue

                    entry = indexed[key]
                    ops_s = entry["ops_s"]
                    ops_s_err = entry["ops_s_err"]
                    gb_s = ops_s * DATA_SIZE_BYTES / 1e9

                    row = [str(val), method, f"{ops_s:.2f}", f"{ops_s_err:.2f}", f"{gb_s:.4f}"]
                    for pk in PERFNORM_KEYS:
                        c = entry["counters"].get(pk, 0)
                        # Normalize to per-byte
                        per_byte = c / DATA_SIZE_BYTES if c else 0
                        row.append(f"{per_byte:.6f}")
                    out.write("\t".join(row) + "\n")

        print(f"  wrote {tsv_path}")


if __name__ == "__main__":
    main()
