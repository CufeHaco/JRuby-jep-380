#!/bin/bash
B=/data/valen-systems-shared/worktrees/cufehaco/JRuby-jep-380/bench_long.rb
P=/data/valen-systems-shared/worktrees/cufehaco/jruby-upstream/bin/jruby
O=/tmp/kj-benchlong; rm -rf $O; mkdir -p $O
PIN="numactl --membind=0 taskset -c 2,3,4,5"
run() { case $1 in
  patched) BENCH_SEED=$2 $PIN $P -J--enable-native-access=ALL-UNNAMED -J-Xshare:off $B ;;
  stock)   BENCH_SEED=$2 $PIN /usr/local/bin/jruby -J-Xshare:off $B ;;
  mri)     BENCH_SEED=$2 $PIN ruby $B ;;
esac > $O/$1-$3.log 2>&1; }
uptime > $O/load-start.txt
for i in $(seq 1 10); do
  for rt in $(printf "patched\nstock\n" | shuf); do run $rt $((RANDOM * 32768 + RANDOM)) $i; echo "round $i $rt done $(uptime | sed "s/.*load/load/")"; done
done
for i in 1 2 3; do run mri $((RANDOM * 32768 + RANDOM)) $i; done
uptime > $O/load-end.txt; echo DONE
