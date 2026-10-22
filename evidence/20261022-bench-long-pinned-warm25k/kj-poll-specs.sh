#!/bin/bash
U=/data/valen-systems-shared/worktrees/cufehaco/jruby-upstream
O=/tmp/kj-poll-specs; rm -rf $O; mkdir -p $O; cd $U
for spec in core/io core/thread core/kernel/select_spec.rb core/kernel/sleep_spec.rb library/socket library/timeout library/io-wait; do
  n=$(echo $spec | tr / _)
  for J in patched stock; do bin=$U/bin/jruby; [ $J = stock ] && bin=/usr/local/bin/jruby
    timeout 900 $bin -J--enable-native-access=ALL-UNNAMED -J-Xshare:off spec/mspec/bin/mspec-run -B spec/jruby.mspec spec/ruby/$spec > $O/$n-$J.log 2>&1
    echo "$spec $J rc=$? $(grep -aE 'files?, .*examples' $O/$n-$J.log | tail -1)"
  done
done
echo SPECDONE
