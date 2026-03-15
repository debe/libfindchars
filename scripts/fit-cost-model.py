#!/usr/bin/env python3
"""Fit cost models: instructions/byte as a function of sweep parameters.

SIMD Compiled, Regex, and Regex+Conv use a linear model:
    instructions/byte = a + b*density + c*rounds

SIMD C2 JIT uses a quadratic model (ascii_count and mb_count are direct cost
drivers because the JIT can't inline across virtual dispatch):
    instructions/byte = a + b*density + c*ascii + d*ascii² + e*mb²

Reads rows from all four sweep TSVs and fits via OLS.
Outputs coefficients, R², observed vs predicted tables, and gnuplot-ready data.
"""

import csv
import os
import sys

import numpy as np


# Baseline values (must match parse-sweep.py)
BASELINE = {"ascii": 8, "density": 15, "mb": 0, "groups": 2}

# Sweep definitions: (filename, param_index)
SWEEPS = [
    ("sweep-ascii-count.tsv", 0),
    ("sweep-density.tsv",     1),
    ("sweep-multibyte.tsv",   2),
    ("sweep-groups.tsv",      3),
]


def compute_rounds(ascii_count, mb_count):
    """Compute number of shuffle rounds for a given configuration."""
    return (2 if ascii_count > 10 else 1) + mb_count


def read_rows(data_dir, method):
    """Read rows for a method from sweep TSVs.

    Returns list of (density, rounds, insn_per_byte, ascii_count, mb_count).
    """
    rows = []
    seen = set()

    for filename, param_idx in SWEEPS:
        path = os.path.join(data_dir, filename)
        if not os.path.exists(path):
            print(f"  WARNING: {path} not found, skipping", file=sys.stderr)
            continue

        with open(path) as f:
            reader = csv.DictReader(f, delimiter="\t")
            for row in reader:
                if row["method"] != method:
                    continue

                param_val = int(row["param"])

                config = [BASELINE["ascii"], BASELINE["density"],
                          BASELINE["mb"], BASELINE["groups"]]
                config[param_idx] = param_val
                ascii_count, density, mb_count, groups = config

                config_key = tuple(config)
                if config_key in seen:
                    continue
                seen.add(config_key)

                insn_per_byte = float(row["instructions"])
                if insn_per_byte == 0:
                    continue

                rounds = compute_rounds(ascii_count, mb_count)
                rows.append((density / 100.0, rounds, insn_per_byte,
                             ascii_count, mb_count))

    return rows


def fit_linear(rows, name):
    """Fit instructions/byte = a + b*density + c*rounds."""
    densities = np.array([r[0] for r in rows])
    rounds = np.array([r[1] for r in rows])
    observed = np.array([r[2] for r in rows])

    A = np.column_stack([np.ones(len(rows)), densities, rounds])
    coeffs, _, _, _ = np.linalg.lstsq(A, observed, rcond=None)
    a, b, c = coeffs

    predicted = A @ coeffs
    ss_res = np.sum((observed - predicted) ** 2)
    ss_tot = np.sum((observed - np.mean(observed)) ** 2)
    r_squared = 1 - ss_res / ss_tot if ss_tot > 0 else 0

    print(f"\n=== {name}: instructions/byte = a + b*density + c*rounds ===")
    print(f"  a (intercept) = {a:.4f}")
    print(f"  b (density)   = {b:.4f}")
    print(f"  c (rounds)    = {c:.4f}")
    print(f"  R²            = {r_squared:.4f}")
    print(f"  N             = {len(rows)}")
    print(f"  gnuplot: f(d,r) = {a:.4f} + {b:.4f}*d + {c:.4f}*r")

    return coeffs, r_squared, predicted


def fit_c2jit(rows, name):
    """Fit instructions/byte = a + b*density + c*ascii + d*ascii² + e*mb².

    The C2 JIT engine doesn't inline across virtual dispatch, so cost scales
    directly with ascii_count (quadratically) and mb_count (quadratically),
    not just the binary round-split threshold.
    """
    densities = np.array([r[0] for r in rows])
    observed = np.array([r[2] for r in rows])
    ascii_arr = np.array([r[3] for r in rows], dtype=float)
    mb_arr = np.array([r[4] for r in rows], dtype=float)

    A = np.column_stack([np.ones(len(rows)), densities, ascii_arr,
                         ascii_arr**2, mb_arr**2])
    coeffs, _, _, _ = np.linalg.lstsq(A, observed, rcond=None)
    a, b, c, d, e = coeffs

    predicted = A @ coeffs
    ss_res = np.sum((observed - predicted) ** 2)
    ss_tot = np.sum((observed - np.mean(observed)) ** 2)
    r_squared = 1 - ss_res / ss_tot if ss_tot > 0 else 0

    print(f"\n=== {name}: instructions/byte = a + b*density + c*ascii + d*ascii² + e*mb² ===")
    print(f"  a (intercept) = {a:.4f}")
    print(f"  b (density)   = {b:.4f}")
    print(f"  c (ascii)     = {c:.4f}")
    print(f"  d (ascii²)    = {d:.4f}")
    print(f"  e (mb²)       = {e:.4f}")
    print(f"  R²            = {r_squared:.4f}")
    print(f"  N             = {len(rows)}")
    print(f"  gnuplot: f(d,a,m) = {a:.4f} + {b:.4f}*d + {c:.4f}*a + {d:.4f}*a² + {e:.4f}*m²")

    return coeffs, r_squared, predicted


def main():
    if len(sys.argv) < 2:
        print("Usage: fit-cost-model.py <sweep-data-dir>", file=sys.stderr)
        sys.exit(1)

    data_dir = sys.argv[1]

    # Fit linear models for simdCompiled, regex, regexWithConversion
    linear_coefficients = {}
    for method, label, short in [("simdCompiled", "SIMD Compiled", "simd"),
                                 ("regex", "Regex", "regex"),
                                 ("regexWithConversion", "Regex + Conv", "regexconv")]:
        rows = read_rows(data_dir, method)
        if len(rows) < 3:
            print(f"\n  WARNING: {label} has only {len(rows)} data points, skipping fit",
                  file=sys.stderr)
            continue

        coeffs, r_squared, predicted = fit_linear(rows, label)
        linear_coefficients[short] = coeffs

        out_path = os.path.join(data_dir, f"cost-model-{method}.tsv")
        with open(out_path, "w") as f:
            f.write("density\trounds\tobserved\tpredicted\tresidual\n")
            for i, (d, r, obs, _, _) in enumerate(rows):
                pred = predicted[i]
                f.write(f"{d:.2f}\t{r}\t{obs:.6f}\t{pred:.6f}\t{obs - pred:.6f}\n")
        print(f"  wrote {out_path}")

    # Fit quadratic model for C2 JIT
    c2jit_coefficients = None
    c2jit_rows = read_rows(data_dir, "simdC2Jit")
    if len(c2jit_rows) >= 3:
        coeffs, r_squared, predicted = fit_c2jit(c2jit_rows, "SIMD C2 JIT")
        c2jit_coefficients = coeffs

        out_path = os.path.join(data_dir, "cost-model-simdC2Jit.tsv")
        with open(out_path, "w") as f:
            f.write("density\tascii\tmb\tobserved\tpredicted\tresidual\n")
            for i, (d, r, obs, ac, mb) in enumerate(c2jit_rows):
                pred = predicted[i]
                f.write(f"{d:.2f}\t{ac}\t{mb}\t{obs:.6f}\t{pred:.6f}\t{obs - pred:.6f}\n")
        print(f"  wrote {out_path}")

    # Write gnuplot coefficients file
    write_gnuplot_coefficients(data_dir, linear_coefficients, c2jit_coefficients)

    # Write combined plot data for linear models
    linear_methods = [("simdCompiled", "simd"), ("regex", "regex"),
                      ("regexWithConversion", "regexconv")]
    fitted = {}
    for method, short in linear_methods:
        rows = read_rows(data_dir, method)
        if len(rows) >= 3:
            fitted[short] = _fit_linear_raw(rows)

    if len(fitted) >= 2:
        out_path = os.path.join(data_dir, "cost-model-surface.tsv")
        with open(out_path, "w") as f:
            header = ["density", "rounds"] + [f"{s}_predicted" for s in fitted]
            f.write("\t".join(header) + "\n")
            for d_pct in range(5, 55, 5):
                d = d_pct / 100.0
                for r in [1, 2, 3, 4]:
                    row = [f"{d:.2f}", str(r)]
                    for s in fitted:
                        c = fitted[s]
                        row.append(f"{c[0] + c[1] * d + c[2] * r:.4f}")
                    f.write("\t".join(row) + "\n")
        print(f"\n  wrote {out_path}")

    print()


def write_gnuplot_coefficients(data_dir, linear_coefficients, c2jit_coefficients):
    """Write gnuplot include file with fitted coefficients."""
    out_path = os.path.join(data_dir, "cost-model-coefficients.gp")
    with open(out_path, "w") as f:
        f.write("# Auto-generated by fit-cost-model.py — do not edit\n")
        for short, names in [("simd", "abc"), ("regex", "abc"), ("regexconv", "abc")]:
            if short in linear_coefficients:
                c = linear_coefficients[short]
                parts = "; ".join(f"{short}_{n} = {c[i]:.4f}" for i, n in enumerate(names))
                f.write(parts + "\n")
        if c2jit_coefficients is not None:
            c = c2jit_coefficients
            names = "abcde"
            parts = "; ".join(f"c2jit_{n} = {c[i]:.4f}" for i, n in enumerate(names))
            f.write(parts + "\n")
    print(f"  wrote {out_path}")


def _fit_linear_raw(rows):
    """Raw linear fit returning just coefficients."""
    densities = np.array([r[0] for r in rows])
    rounds = np.array([r[1] for r in rows])
    observed = np.array([r[2] for r in rows])
    A = np.column_stack([np.ones(len(rows)), densities, rounds])
    coeffs, _, _, _ = np.linalg.lstsq(A, observed, rcond=None)
    return coeffs


if __name__ == "__main__":
    main()
