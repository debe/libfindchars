set terminal pngcairo size 1200,900 enhanced font "sans,11"
set output "docs/sweep-instructions.png"

set multiplot layout 2,2 title "libfindchars — Instructions/byte vs Sweep Dimensions (10 MB)" font ",14"

set grid y
set key top right font ",10"

# Colors
simd_color = "#2266bb"
c2jit_color = "#228833"
regex_color = "#cc4444"
regexconv_color = "#cc8800"

# TSV columns: 1=param 2=method 3=ops_s 4=ops_s_err 5=gb_s
#              6=instructions 7=cycles 8=branches 9=branch_misses 10=L1_dcache_load_misses

# --- ASCII literal count ---
set title "ASCII Literal Count" font ",12"
set xlabel "ASCII literals"
set ylabel "instructions/byte"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv", \
     "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-ascii-count.tsv" \
     using 1:6:(sprintf("%.1f", column(6))) with labels offset 0,1.0 font ",9" notitle

# --- Target density ---
set title "Target Density" font ",12"
set xlabel "Density (%)"
set ylabel "instructions/byte"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-density.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-density.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-density.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-density.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv", \
     "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-density.tsv" \
     using 1:6:(sprintf("%.1f", column(6))) with labels offset 0,1.0 font ",9" notitle

# --- Multi-byte codepoints ---
set title "Multi-byte Codepoints" font ",12"
set xlabel "Multi-byte count"
set ylabel "instructions/byte"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv", \
     "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-multibyte.tsv" \
     using 1:6:(sprintf("%.1f", column(6))) with labels offset 0,1.0 font ",9" notitle

# --- Group count ---
set title "Group Count" font ",12"
set xlabel "Groups"
set ylabel "instructions/byte"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb simd_color title "SIMD Compiled", \
     "< awk -F'\\t' '$2==\"simdC2Jit\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb c2jit_color title "SIMD C2 JIT", \
     "< awk -F'\\t' '$2==\"regex\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regex_color title "Regex", \
     "< awk -F'\\t' '$2==\"regexWithConversion\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb regexconv_color title "Regex + Conv", \
     "< awk -F'\\t' '$2==\"simdCompiled\"' docs/sweep-data/sweep-groups.tsv" \
     using 1:6:(sprintf("%.1f", column(6))) with labels offset 0,1.0 font ",9" notitle

unset multiplot
