set terminal pngcairo size 1400,600 enhanced font "sans,11"
set output "docs/sweep-cost-model-combined.png"

set multiplot layout 1,2 title "libfindchars — Predicted vs Observed Cost (instructions/byte)" font ",14"

# ---- Left panel: SIMD engines (low range) ----
set title "SIMD Engines" font ",12"
set xlabel "Predicted (insn/byte)"
set ylabel "Observed (insn/byte)"
set autoscale
set grid
set key top left font ",9" spacing 1.2

plot \
    x with lines lw 1.5 lc rgb "#aaaaaa" dt 2 title "y = x", \
    "docs/sweep-data/cost-model-simdCompiled.tsv" \
        using 4:3 with points pt 7 ps 1.2 lc rgb "#2266bb" title "SIMD Compiled", \
    "docs/sweep-data/cost-model-simdC2Jit.tsv" \
        using 5:4 with points pt 5 ps 1.2 lc rgb "#22aa55" title "SIMD C2 JIT"

# ---- Right panel: All four engines (log scale) ----
set title "All Engines (log scale)" font ",12"
set xlabel "Predicted (insn/byte)"
set ylabel "Observed (insn/byte)"
set autoscale
set logscale xy
set grid

plot \
    x with lines lw 1.5 lc rgb "#aaaaaa" dt 2 title "y = x", \
    "docs/sweep-data/cost-model-simdCompiled.tsv" \
        using 4:3 with points pt 7 ps 1.0 lc rgb "#2266bb" title "SIMD Compiled", \
    "docs/sweep-data/cost-model-simdC2Jit.tsv" \
        using 5:4 with points pt 5 ps 1.0 lc rgb "#22aa55" title "SIMD C2 JIT", \
    "docs/sweep-data/cost-model-regex.tsv" \
        using 4:3 with points pt 9 ps 1.0 lc rgb "#cc3333" title "Regex", \
    "docs/sweep-data/cost-model-regexWithConversion.tsv" \
        using 4:3 with points pt 11 ps 1.0 lc rgb "#cc8800" title "Regex + Conv"

unset logscale
unset multiplot
