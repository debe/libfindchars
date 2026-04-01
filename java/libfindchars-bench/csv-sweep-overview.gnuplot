set terminal pngcairo size 1400,500 enhanced font "sans,11"
set output "docs/csv-sweep-overview.png"

set multiplot layout 1,3 title "libfindchars CSV — Parameter Sweep (100 MB, JDK 25)" font ",14"

set grid y
set key top right font ",10"
set style data linespoints
set pointsize 0.8

# Colors
parse_color = "#228833"
fast_color = "#cc4444"

# --- Sweep A: Column count ---
set title "Column Count" font ",12"
set xlabel "Columns"
set ylabel "Throughput (GB/s)"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-columns.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb parse_color title "SIMD Parse", \
     "< awk -F'\\t' '$2==\"fastCsv\"' docs/csv-sweep-data/csv-sweep-columns.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb fast_color title "FastCSV"

# --- Sweep B: Quote percentage ---
set title "Quote Percentage" font ",12"
set xlabel "Quote %"
set ylabel "Throughput (GB/s)"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-quotes.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb parse_color title "SIMD Parse", \
     "< awk -F'\\t' '$2==\"fastCsv\"' docs/csv-sweep-data/csv-sweep-quotes.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb fast_color title "FastCSV"

# --- Sweep C: Average field length ---
set title "Average Field Length" font ",12"
set xlabel "Avg field length"
set ylabel "Throughput (GB/s)"
set yrange [0:*]
plot "< awk -F'\\t' '$2==\"simdParse\"' docs/csv-sweep-data/csv-sweep-fieldlen.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb parse_color title "SIMD Parse", \
     "< awk -F'\\t' '$2==\"fastCsv\"' docs/csv-sweep-data/csv-sweep-fieldlen.tsv" \
     using 1:5 with linespoints lw 2 pt 5 ps 0.8 lc rgb fast_color title "FastCSV"

unset multiplot
