set terminal pngcairo size 1200,900 enhanced font "sans,11"
set output "docs/sweep-overview.png"

set multiplot layout 2,2 title "libfindchars — Parameter Sweep (10 MB, JDK 25, AVX-512)" font ",14"

set grid y
set key top right font ",10"
set style data linespoints
set pointsize 0.8

# Colors
simd_color = "#2266bb"
c2jit_color = "#228833"
regex_color = "#cc4444"
regexconv_color = "#cc8800"

# --- Sweep A: ASCII literal count ---
set title "ASCII Literal Count" font ",12"
set xlabel "ASCII literals"
set ylabel "Throughput (GB/s)"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv"

# --- Sweep B: Target density ---
set title "Target Density" font ",12"
set xlabel "Density (%)"
set ylabel "Throughput (GB/s)"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-density.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-density.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-density.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-density.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv"

# --- Sweep C: Multi-byte codepoints ---
set title "Multi-byte Codepoints" font ",12"
set xlabel "Multi-byte count"
set ylabel "Throughput (GB/s)"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv"

# --- Sweep D: Group count ---
set title "Group Count" font ",12"
set xlabel "Groups"
set ylabel "Throughput (GB/s)"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv"

unset multiplot
