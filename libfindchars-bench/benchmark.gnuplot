set terminal pngcairo size 800,480 enhanced font "sans,12"
set output "libfindchars-bench/benchmark.png"

set style data histogram
set style histogram clustered gap 1
set style fill solid 0.8 border -1
set boxwidth 0.7

set title "libfindchars 0.3.0 — Character Detection Throughput\n{/*0.8 3 MB mixed ASCII/UTF-8 file, JDK 25, AVX-512, single core}" font ",13"
set ylabel "Throughput (GB/s)" font ",12"
set yrange [0:2.4]
set grid y
set key off

# Convert ops/s on 3MB file to GB/s: score * 3_000_000 / 1_000_000_000
# regex:               32.90  * 3e6 / 1e9 = 0.099
# simdAscii:          672.17  * 3e6 / 1e9 = 2.017
# simdUtf8:           585.73  * 3e6 / 1e9 = 1.757

set xtics font ",12"
set bmargin 4

$DATA << EOD
"Regex"             0.099
"ASCII (compiled)"  2.017
"UTF-8 (compiled)"  1.757
"ASCII (C2 JIT)"    0.000
"UTF-8 (C2 JIT)"    0.000
EOD

plot $DATA using 2:xtic(1) with boxes lc rgb "#2266bb" notitle, \
     '' using 0:2:(sprintf("%.1f GB/s", $2)) with labels offset 0,0.8 font ",11" notitle
