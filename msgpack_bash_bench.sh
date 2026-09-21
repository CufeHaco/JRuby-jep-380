#!/usr/bin/env bash
# Throughput benchmark for the bash client: N round trips of a small
# fixed message over one warm connection. N is kept small by default --
# each round trip forks several external processes (head, od, mktemp)
# for the socket read, which dominates wall time far more than the
# socket I/O itself; see msgpack_bash_client.sh for why that's needed.
set -euo pipefail

SOCK_PATH="${1:?usage: msgpack_bash_bench.sh /path/to/socket [N]}"
N="${2:-20}"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

coproc NC { nc -U "$SOCK_PATH"; }
exec {READFD}<&${NC[0]}
exec {WRITEFD}>&${NC[1]}

hexbyte() { printf '\\x%02x' "$1"; }

# Fixed frame for {"engine"=>"Bash","seq"=>0,"msg"=>"ping"} -- precomputed
# once outside the timed loop since it never changes between iterations.
build_ping_frame() {
    local payload_len=$(( 1 + (1+6) + (1+4) + (1+3) + 1 + (1+3) + (1+4) ))
    local b0=$(( (payload_len >> 24) & 0xff )) b1=$(( (payload_len >> 16) & 0xff ))
    local b2=$(( (payload_len >> 8) & 0xff ))  b3=$(( payload_len & 0xff ))
    {
        printf '%b' "$(hexbyte "$b0")$(hexbyte "$b1")$(hexbyte "$b2")$(hexbyte "$b3")"
        printf '%b' "$(hexbyte 0x83)"
        printf '%b' "$(hexbyte 0xa6)"; printf '%s' "engine"
        printf '%b' "$(hexbyte 0xa4)"; printf '%s' "Bash"
        printf '%b' "$(hexbyte 0xa3)"; printf '%s' "seq"
        printf '%b' "$(hexbyte 0)"
        printf '%b' "$(hexbyte 0xa3)"; printf '%s' "msg"
        printf '%b' "$(hexbyte 0xa4)"; printf '%s' "ping"
    } > "$TMPDIR/ping_frame.bin"
}

build_ping_frame

read_n_bytes() {
    local n=$1
    head -c "$n" <&"$READFD" > "$TMPDIR/discard.bin"
}

t0=$(date +%s.%N)
for (( i=0; i<N; i++ )); do
    cat "$TMPDIR/ping_frame.bin" >&"$WRITEFD"
    read_n_bytes 4
    len=$(od -An -tu4 --endian=big -v "$TMPDIR/discard.bin" | tr -d ' ')
    read_n_bytes "$len"
done
t1=$(date +%s.%N)

secs=$(echo "$t1 - $t0" | bc)
throughput=$(echo "scale=1; $N / $secs" | bc)
mean_ms=$(echo "scale=4; ($secs / $N) * 1000" | bc)
printf 'BENCH(Bash): n=%d time=%ss throughput=%s msg/s mean=%sms\n' "$N" "$secs" "$throughput" "$mean_ms"
