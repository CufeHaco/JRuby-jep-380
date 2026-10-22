#!/bin/bash
R=/data/valen-systems-shared/worktrees/cufehaco/jruby-jep380-rebase
B=/data/valen-systems-shared/worktrees/cufehaco/jruby-master-baseline
O=/tmp/kj-rebase-tests; rm -rf $O; mkdir -p $O
SUITES="library/socket/unixsocket library/socket/unixserver library/socket/socket library/socket/basicsocket library/socket/addrinfo library/socket/ancillarydata library/socket/constants library/socket/ipsocket library/socket/option library/socket/tcpserver library/socket/tcpsocket library/socket/udpsocket core/io core/thread core/fiber core/kernel core/exception core/method core/unboundmethod core/proc core/module core/binding language library/timeout library/io-wait"
for spec in $SUITES; do n=$(echo $spec | tr / _)
  for J in rebase baseline; do T=$R; [ $J = baseline ] && T=$B
    (cd $T && timeout 900 bin/jruby -J--enable-native-access=ALL-UNNAMED -J-Xshare:off spec/mspec/bin/mspec-run -B spec/jruby.mspec spec/ruby/$spec) > $O/$n-$J.log 2>&1
    echo "$spec $J rc=$? $(grep -aE 'files?, .*examples' $O/$n-$J.log | tail -1)"
  done
  grep -aE " (FAILED|ERROR)$" $O/$n-rebase.log | sort -u > $O/$n.r; grep -aE " (FAILED|ERROR)$" $O/$n-baseline.log | sort -u > $O/$n.b
  only=$(comm -23 $O/$n.r $O/$n.b); [ -n "$only" ] && { echo "  REBASE-ONLY in $spec:"; echo "$only" | sed 's/^/    /'; }
done
echo SPECDONE
H=/data/valen-systems-shared/worktrees/cufehaco/Kestowv0.5.6-JEP380-harness
sed -e "s#^U=.*#U=$R#" -e "s#^STOCK=.*#STOCK=$B/bin/jruby#" -e 's#pre-pr-gate; mkdir#rebase-gate; mkdir#' $H/jep380-pre-pr-gate.sh > /tmp/kj-rebase-gate.sh
cd $H && bash /tmp/kj-rebase-gate.sh
echo ALLDONE
