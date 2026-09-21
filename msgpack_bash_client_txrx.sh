#!/usr/bin/env bash
# Bash client for jruby_msgpack_server_txrx.rb -- two separate socket
# connections (c2s outgoing, s2c incoming) instead of one bidirectional
# connection. Same nc-as-dumb-pipe + real bash MessagePack logic as
# msgpack_bash_client.sh.
set -euo pipefail

C2S_PATH="${1:?usage: msgpack_bash_client_txrx.sh /path/to/c2s.sock /path/to/s2c.sock}"
S2C_PATH="${2:?usage: msgpack_bash_client_txrx.sh /path/to/c2s.sock /path/to/s2c.sock}"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

coproc C2S { nc -U "$C2S_PATH"; }
# Bash may print "coproc [...] still exists" here -- a benign wart from
# tracking two named coprocs at once, not a functional problem.
coproc S2C { nc -U "$S2C_PATH"; }
# See msgpack_bash_client.sh: duplicate onto independent fd numbers so a
# later subshell/child reap doesn't silently close the coproc's fds.
exec {WRITEFD}>&${C2S[1]}
exec {READFD}<&${S2C[0]}

echo "CLIENT(Bash-txrx): connected c2s=$C2S_PATH s2c=$S2C_PATH"

hexbyte() { printf '\\x%02x' "$1"; }

mp_str_header() {
    local len=$1 fmt=""
    if (( len <= 31 )); then
        fmt+=$(hexbyte $(( 0xa0 | len )))
    elif (( len <= 255 )); then
        fmt+=$(hexbyte 0xd9)$(hexbyte "$len")
    else
        fmt+=$(hexbyte 0xda)$(hexbyte $(( (len >> 8) & 0xff )))$(hexbyte $(( len & 0xff )))
    fi
    printf '%b' "$fmt"
}

send_frame() {
    local seq=$1 engine=$2 msg=$3
    local elen=${#engine} mlen=${#msg}
    local mhdr
    if (( mlen <= 31 )); then mhdr=1; elif (( mlen <= 255 )); then mhdr=2; else mhdr=3; fi
    local payload_len=$(( 1 + (1+6) + (1+elen) + (1+3) + 1 + (1+3) + mhdr + mlen ))
    local b0=$(( (payload_len >> 24) & 0xff )) b1=$(( (payload_len >> 16) & 0xff ))
    local b2=$(( (payload_len >> 8) & 0xff ))  b3=$(( payload_len & 0xff ))
    {
        printf '%b' "$(hexbyte "$b0")$(hexbyte "$b1")$(hexbyte "$b2")$(hexbyte "$b3")"
        printf '%b' "$(hexbyte 0x83)"
        mp_str_header 6; printf '%s' "engine"
        mp_str_header "$elen"; printf '%s' "$engine"
        mp_str_header 3; printf '%s' "seq"
        printf '%b' "$(hexbyte "$seq")"
        mp_str_header 3; printf '%s' "msg"
        mp_str_header "$mlen"; printf '%s' "$msg"
    } >&"$WRITEFD"
}

read_n_bytes_to_array() {
    local n=$1
    local -n arr_ref=$2
    local tmpfile
    tmpfile=$(mktemp "$TMPDIR/read.XXXXXX")
    head -c "$n" <&"$READFD" > "$tmpfile"
    arr_ref=( $(od -An -tu1 -v "$tmpfile") )
    rm -f "$tmpfile"
}

bytes_of() { printf '%s' "$1" | od -An -tu1 -v; }

bytes_to_str() {
    (( $# == 0 )) && return
    printf -v _esc '\\%03o' "$@"
    printf -v _out '%b' "$_esc"
    printf '%s' "$_out"
}

run_test() {
    local seq=$1 engine=$2 msg=$3
    send_frame "$seq" "$engine" "$msg"

    local -a hdr
    read_n_bytes_to_array 4 hdr
    local length=$(( (hdr[0] << 24) | (hdr[1] << 16) | (hdr[2] << 8) | hdr[3] ))

    local -a payload
    read_n_bytes_to_array "$length" payload

    local pos=0
    pos=$(( pos + 1 + 1 + 6 ))
    local etag=${payload[$pos]}
    local elen=$(( etag & 0x1f ))
    pos=$(( pos + 1 ))
    local -a engine_bytes=( "${payload[@]:pos:elen}" )
    pos=$(( pos + elen ))

    pos=$(( pos + 1 + 3 ))
    local seq_val=${payload[$pos]}
    pos=$(( pos + 1 ))

    pos=$(( pos + 1 + 3 ))
    local mtag=${payload[$pos]} mhdr mlen
    if (( mtag == 217 )); then
        mlen=${payload[$((pos+1))]}; mhdr=2
    elif (( mtag == 218 )); then
        mlen=$(( (payload[$((pos+1))] << 8) | payload[$((pos+2))] )); mhdr=3
    else
        mlen=$(( mtag & 0x1f )); mhdr=1
    fi
    pos=$(( pos + mhdr ))
    local -a msg_bytes=( "${payload[@]:pos:mlen}" )

    local -a expected_engine_bytes=( $(bytes_of "JRuby") )
    local -a expected_msg_bytes=( $(bytes_of "JRuby-echo:$msg") )

    local ok=true
    [[ "${engine_bytes[*]}" == "${expected_engine_bytes[*]}" ]] || ok=false
    [[ "$seq_val" == "$seq" ]] || ok=false
    [[ "${msg_bytes[*]}" == "${expected_msg_bytes[*]}" ]] || ok=false

    local engine_str
    engine_str=$(bytes_to_str "${engine_bytes[@]}")

    printf 'CLIENT(Bash-txrx): seq=%d sent=%dB reply=%dB engine=%s match=%s\n' \
        "$seq" "${#msg}" "$mlen" "$engine_str" "$ok"

    if [[ "$ok" != true ]]; then
        echo "MISMATCH detected, aborting" >&2
        exit 1
    fi
}

run_test 0 "Bash" "hello #0 from bash txrx"
run_test 1 "Bash" "second message from bash txrx"
run_test 2 "Bash" "$(printf '%*s' 5000 '' | tr ' ' X)"

echo "CLIENT(Bash-txrx): all messages round-tripped correctly"
