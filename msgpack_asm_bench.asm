; Throughput benchmark: N round trips of a small fixed message over one
; warm connection to jruby_msgpack_bench_server.rb. Raw syscalls only,
; no libc -- same approach as msgpack_asm_client.asm. N is a compile-time
; constant (no decimal argv parsing needed for a benchmark harness).
;
; Usage: msgpack_asm_bench /path/to/socket
BITS 64
global _start

%define BENCH_N 5000

section .data
; {"engine"=>"C","seq"=>0,"msg"=>"ping"} -- fixed frame, sent BENCH_N times.
ping_frame:
    db 0, 0, 0, 24, 131, 166, 101, 110, 103, 105, 110, 101, 161, 67
    db 163, 115, 101, 113, 0, 163, 109, 115, 103, 164, 112, 105, 110, 103
ping_frame_len: equ $-ping_frame

str_result: db "BENCH(ASM): n="
str_result_len: equ $-str_result
str_time: db " time="
str_time_len: equ $-str_time
str_secfrac: db "."
str_throughput: db "s throughput="
str_throughput_len: equ $-str_throughput
str_msgs: db " msg/s"
str_msgs_len: equ $-str_msgs
str_nl: db 10

section .bss
sockaddr_un: resb 110
len_hdr_buf: resb 4
discard_buf: resb 256
udec_buf: resb 24
ts_start: resb 16     ; struct timespec {long tv_sec; long tv_nsec;}
ts_end:   resb 16

section .text
_start:
    lea rdi, [sockaddr_un]
    xor rax, rax
    mov rcx, 110/8
    rep stosq
    mov word [sockaddr_un], 1

    mov rsi, [rsp+16]
    lea rdi, [sockaddr_un+2]
.copy_path:
    mov al, [rsi]
    mov [rdi], al
    inc rsi
    inc rdi
    test al, al
    jnz .copy_path

    mov rax, 41
    mov rdi, 1
    mov rsi, 1
    xor rdx, rdx
    syscall
    mov r12, rax                       ; r12 = fd

    mov rax, 42
    mov rdi, r12
    lea rsi, [sockaddr_un]
    mov rdx, 110
    syscall
    cmp rax, 0
    jl .exit_fail

    ; clock_gettime(CLOCK_MONOTONIC, &ts_start)
    mov rax, 228
    mov rdi, 1
    lea rsi, [ts_start]
    syscall

    mov r13, BENCH_N
.loop:
    mov rdi, r12
    lea rsi, [ping_frame]
    mov rdx, ping_frame_len
    call write_all

    mov rdi, r12
    lea rsi, [len_hdr_buf]
    mov rdx, 4
    call read_all

    movzx r14, byte [len_hdr_buf]
    shl r14, 8
    movzx rax, byte [len_hdr_buf+1]
    or r14, rax
    shl r14, 8
    movzx rax, byte [len_hdr_buf+2]
    or r14, rax
    shl r14, 8
    movzx rax, byte [len_hdr_buf+3]
    or r14, rax

    mov rdi, r12
    lea rsi, [discard_buf]
    mov rdx, r14
    call read_all

    dec r13
    jnz .loop

    ; clock_gettime(CLOCK_MONOTONIC, &ts_end)
    mov rax, 228
    mov rdi, 1
    lea rsi, [ts_end]
    syscall

    ; nanos = (end.sec - start.sec) * 1e9 + (end.nsec - start.nsec)
    mov rax, [ts_end]
    sub rax, [ts_start]
    mov rbx, 1000000000
    mul rbx                            ; rax = whole-seconds delta in ns
    mov r15, rax
    mov rax, [ts_end+8]
    sub rax, [ts_start+8]
    add r15, rax                       ; r15 = total nanoseconds elapsed
    ; (kept in r15, not rcx/r11: syscall clobbers r11/rcx, and print_udec
    ; also uses rcx internally as its digit divisor -- either would destroy
    ; a value stashed in rcx across any of the print_* calls below)

    ; print "BENCH(ASM): n=5000 time=X.YYYYs throughput=Zmsg/s"
    lea rdi, [str_result]
    mov rsi, str_result_len
    call print_str
    mov rdi, BENCH_N
    call print_udec

    lea rdi, [str_time]
    mov rsi, str_time_len
    call print_str
    mov rax, r15
    xor rdx, rdx
    mov rbx, 1000000000
    div rbx                            ; rax = whole seconds, rdx = remainder ns
    mov r14, rdx                       ; save remainder -- print_udec below clobbers rdx
    mov rdi, rax
    call print_udec
    lea rdi, [str_secfrac]
    mov rsi, 1
    call print_str
    mov rdi, r14
    call print_udec_padded9

    lea rdi, [str_throughput]
    mov rsi, str_throughput_len
    call print_str
    ; throughput = BENCH_N * 1e9 / total_ns
    mov rax, BENCH_N
    mov rbx, 1000000000
    mul rbx
    div r15
    mov rdi, rax
    call print_udec

    lea rdi, [str_msgs]
    mov rsi, str_msgs_len
    call print_str
    lea rdi, [str_nl]
    mov rsi, 1
    call print_str

    mov rax, 60
    xor rdi, rdi
    syscall

.exit_fail:
    mov rax, 60
    mov rdi, 1
    syscall

; write_all(rdi=fd, rsi=buf, rdx=len)
write_all:
.wa_loop:
    cmp rdx, 0
    je .wa_done
    mov rax, 1
    syscall
    cmp rax, 0
    jle .wa_err
    add rsi, rax
    sub rdx, rax
    jmp .wa_loop
.wa_done:
    ret
.wa_err:
    mov rax, 60
    mov rdi, 2
    syscall

; read_all(rdi=fd, rsi=buf, rdx=len)
read_all:
.ra_loop:
    cmp rdx, 0
    je .ra_done
    mov rax, 0
    syscall
    cmp rax, 0
    jle .ra_err
    add rsi, rax
    sub rdx, rax
    jmp .ra_loop
.ra_done:
    ret
.ra_err:
    mov rax, 60
    mov rdi, 3
    syscall

; print_str(rdi=ptr, rsi=len)
print_str:
    mov rdx, rsi
    mov rsi, rdi
    mov rdi, 1
    mov rax, 1
    syscall
    ret

; print_udec(rdi=unsigned 64-bit value)
print_udec:
    mov rax, rdi
    lea rsi, [udec_buf+23]
    mov rcx, 10
    xor r8, r8
.pu_loop:
    xor rdx, rdx
    div rcx
    add dl, '0'
    dec rsi
    mov [rsi], dl
    inc r8
    test rax, rax
    jnz .pu_loop
    mov rdi, rsi
    mov rsi, r8
    call print_str
    ret

; print_udec_padded9(rdi=value 0..999999999) -- zero-padded to 9 digits,
; for the fractional-seconds part of the elapsed-time printout.
print_udec_padded9:
    mov rax, rdi
    lea rdi, [udec_buf]
    mov rcx, 9
.pad_loop:
    xor rdx, rdx
    mov rbx, 10
    push rax
    ; compute divisor 10^(rcx-1)
    mov rax, 1
    mov r9, rcx
    dec r9
.pow_loop:
    test r9, r9
    jz .pow_done
    imul rax, rbx
    dec r9
    jmp .pow_loop
.pow_done:
    mov rbx, rax
    pop rax
    xor rdx, rdx
    div rbx
    add al, '0'
    mov [rdi], al
    inc rdi
    mov rax, rdx
    dec rcx
    jnz .pad_loop
    lea rdi, [udec_buf]
    mov rsi, 9
    call print_str
    ret
