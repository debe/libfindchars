set terminal pngcairo size 1400,500 enhanced font "sans,11"
set output "docs/csv-sweep-instructions.png"

set multiplot layout 1,3 title "libfindchars CSV — Instructions/byte vs Sweep Dimensions (100 MB)" font ",14"

set grid y
set key top right font ",10"

# Colors
parse_color = "#228833"
fast_color = "#cc4444"

# TSV columns: 1=param 2=method 3=ops_s 4=ops_s_err 5=gb_s
#              6=instructions 7=cycles 8=branches 9=branch_misses 10=L1_dcache_load_misses

# --- Sweep A: Column count ---
set title "Column Count" font ",12"
set xlabel "Columns"
set ylabel "instructions/byte"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-columns.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb parse_color title "SIMD Parse", \
     "< awk -F'\\t' '$2==\"fastCsv\"' docs/csv-sweep-data/csv-sweep-columns.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb fast_color title "FastCSV", \
     "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-columns.tsv" \
     using 1:6:(sprintf("%.1f", column(6))) with labels offset 0,1.0 font ",9" notitle

# --- Sweep B: Quote percentage ---
set title "Quote Percentage" font ",12"
set xlabel "Quote %"
set ylabel "instructions/byte"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-quotes.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb parse_color title "SIMD Parse", \
     "< awk -F'\\t' '$2==\"fastCsv\"' docs/csv-sweep-data/csv-sweep-quotes.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb fast_color title "FastCSV", \
     "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-quotes.tsv" \
     using 1:6:(sprintf("%.1f", column(6))) with labels offset 0,1.0 font ",9" notitle

# --- Sweep C: Average field length ---
set title "Average Field Length" font ",12"
set xlabel "Avg field length"
set ylabel "instructions/byte"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-fieldlen.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb parse_color title "SIMD Parse", \
     "< awk -F'\\t' '$2==\"fastCsv\"' docs/csv-sweep-data/csv-sweep-fieldlen.tsv" \
     using 1:6 with linespoints lw 2 pt 5 ps 0.8 lc rgb fast_color title "FastCSV", \
     "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-fieldlen.tsv" \
     using 1:6:(sprintf("%.1f", column(6))) with labels offset 0,1.0 font ",9" notitle

unset multiplot
