#!/usr/bin/env bash
# Bash client for the JRuby JEP-380 UNIXServer (jruby_msgpack_server.rb).
#
# Bash has no builtin AF_UNIX socket support (unlike /dev/tcp for TCP), so
# `nc -U` is used purely as a dumb byte pipe via a coproc -- same "external
# tool carries no protocol knowledge" pattern as earlier tests. Every bit
# of MessagePack encoding/decoding (fixmap/fixstr/str8/str16/fixint,
# length framing, field comparison) is real bash logic below.
#
# Large payloads are handled via byte-array slicing/comparison rather than
# per-character loops -- a naive char-by-char printf loop over 5000 bytes
# in bash is slow enough to matter; array-slice + single string compare is not.
set -euo pipefail

SOCK_PATH="${1:?usage: msgpack_bash_client.sh /path/to/socket}"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

coproc NC { nc -U "$SOCK_PATH"; }
# Bash silently closes a coproc's fds as a side effect of reaping any later
# subshell/child (command substitutions, pipelines, etc.) -- duplicating
# onto independent fd numbers here avoids that.
exec {READFD}<&${NC[0]}
exec {WRITEFD}>&${NC[1]}

echo "CLIENT(Bash): connected to $SOCK_PATH"

hexbyte() { printf '\\x%02x' "$1"; }

# Prints (as raw bytes) the msgpack string-type header for a given length.
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

# send_frame(seq, engine, msg) -- builds and writes one length-prefixed
# MessagePack frame: {"engine"=>engine, "seq"=>seq, "msg"=>msg}.
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

# read_n_bytes_to_array(n, arrayname) -- reads exactly n raw bytes from the
# socket into a bash array of decimal byte values (nameref output).
read_n_bytes_to_array() {
    local n=$1
    local -n arr_ref=$2
    local tmpfile
    tmpfile=$(mktemp "$TMPDIR/read.XXXXXX")
    head -c "$n" <&"$READFD" > "$tmpfile"
    arr_ref=( $(od -An -tu1 -v "$tmpfile") )
    rm -f "$tmpfile"
}

# bytes_of(str) -- prints the decimal byte values of a string, space-separated.
bytes_of() { printf '%s' "$1" | od -An -tu1 -v; }

# Reconstructs a short ASCII string from an array of decimal byte values
# passed as args -- only used for display, never on the bulk payload.
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
    pos=$(( pos + 1 + 1 + 6 ))                # fixmap tag + "engine" key
    local etag=${payload[$pos]}
    local elen=$(( etag & 0x1f ))
    pos=$(( pos + 1 ))
    local -a engine_bytes=( "${payload[@]:pos:elen}" )
    pos=$(( pos + elen ))

    pos=$(( pos + 1 + 3 ))                    # "seq" key
    local seq_val=${payload[$pos]}
    pos=$(( pos + 1 ))

    pos=$(( pos + 1 + 3 ))                    # "msg" key
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

    printf 'CLIENT(Bash): seq=%d sent=%dB reply=%dB engine=%s match=%s\n' \
        "$seq" "${#msg}" "$mlen" "$engine_str" "$ok"

    if [[ "$ok" != true ]]; then
        echo "MISMATCH detected, aborting" >&2
        exit 1
    fi
}

run_test 0 "Bash" "hello #0 from bash"
run_test 1 "Bash" "second message from bash"
run_test 2 "Bash" "$(printf '%*s' 5000 '' | tr ' ' X)"

echo "CLIENT(Bash): all messages round-tripped correctly"
