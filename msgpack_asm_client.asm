; Real x86-64 assembly client for the JRuby JEP-380 UNIXServer, raw Linux
; syscalls only (no libc). Speaks the same length-prefixed MessagePack
; protocol as msgpack_c_client.c.
;
; Outgoing frames are precomputed static bytes (test payloads are fixed
; test data anyway -- msgpack_c_client.c and the CRuby client used static
; string literals too). What's genuinely dynamic, and the actual proof of
; interop, is the reply: real-time MessagePack parsing and byte comparison
; against what the JRuby server actually echoed back, including the 5000-
; byte case that needs the str16 length form.
;
; Usage: msgpack_asm_client /path/to/socket
BITS 64
global _start

section .data
%include "asm_data.inc"

; ---- per-message test macro (must be defined before use) ----
; %1 out_frame label, %2 out_frame_len, %3 expected_msg label,
; %4 expected_msg_len, %5 seq literal (0/1/2), %6 seq label string (1 byte)
%macro TEST_MESSAGE 6
    mov rdi, r12
    lea rsi, [%1]
    mov rdx, %2
    call write_all

    mov rdi, r12
    lea rsi, [reply_len_buf]
    mov rdx, 4
    call read_all

    movzx r14, byte [reply_len_buf]
    shl r14, 8
    movzx rax, byte [reply_len_buf+1]
    or r14, rax
    shl r14, 8
    movzx rax, byte [reply_len_buf+2]
    or r14, rax
    shl r14, 8
    movzx rax, byte [reply_len_buf+3]
    or r14, rax                       ; r14 = payload length (big-endian decode)

    mov rdi, r12
    lea rsi, [reply_buf]
    mov rdx, r14
    call read_all

    mov r15, 1                        ; match flag, start true

    lea r13, [reply_buf]
    inc r13                           ; skip fixmap tag
    add r13, 7                        ; skip "engine" key (tag+6 bytes)

    cmp byte [r13], 0xa5              ; fixstr len5 ("JRuby")
    je %%engine_tag_ok
    mov r15, 0
%%engine_tag_ok:
    inc r13
    mov rsi, r13
    lea rdi, [str_JRuby]
    mov rcx, 5
    cld
    repe cmpsb
    je %%engine_val_ok
    mov r15, 0
%%engine_val_ok:
    add r13, 5

    add r13, 4                        ; skip "seq" key (tag+3 bytes)
    movzx rax, byte [r13]
    and rax, 0x7f
    cmp rax, %5
    je %%seq_ok
    mov r15, 0
%%seq_ok:
    inc r13

    add r13, 4                        ; skip "msg" key (tag+3 bytes)
    movzx rax, byte [r13]
    cmp al, 0xd9
    je %%msg_hdr_str8
    cmp al, 0xda
    je %%msg_hdr_str16
    ; else: fixstr (0xa0-0xbf), the only other tag this protocol emits
    movzx rbx, al
    and rbx, 0x1f
    inc r13
    jmp %%msg_hdr_done
%%msg_hdr_str8:
    movzx rbx, byte [r13+1]
    add r13, 2
    jmp %%msg_hdr_done
%%msg_hdr_str16:
    movzx rbx, byte [r13+1]
    shl rbx, 8
    movzx rax, byte [r13+2]
    or rbx, rax
    add r13, 3
%%msg_hdr_done:
    ; rbx = actual msg length, r13 = pointer to msg bytes
    cmp rbx, %4
    je %%msg_len_ok
    mov r15, 0
    jmp %%msg_cmp_skip
%%msg_len_ok:
    mov rsi, r13
    lea rdi, [%3]
    mov rcx, rbx
    cld
    repe cmpsb
    je %%msg_cmp_skip
    mov r15, 0
%%msg_cmp_skip:

    ; --- print status line ---
    lea rdi, [str_result_pre]
    mov rsi, str_result_pre_len
    call print_str
    lea rdi, [%6]
    mov rsi, 1
    call print_str

    lea rdi, [str_sent]
    mov rsi, str_sent_len
    call print_str
    mov rdi, %4 - 11                  ; sent length == echoed length minus "JRuby-echo:"
    call print_udec

    lea rdi, [str_reply]
    mov rsi, str_reply_len
    call print_str
    mov rdi, r14
    call print_udec

    lea rdi, [str_bmatch]
    mov rsi, str_bmatch_len
    call print_str
    cmp r15, 1
    jne %%print_false
    lea rdi, [str_true]
    mov rsi, str_true_len
    call print_str
    jmp %%print_match_done
%%print_false:
    lea rdi, [str_false]
    mov rsi, str_false_len
    call print_str
%%print_match_done:
    lea rdi, [str_nl]
    mov rsi, 1
    call print_str

    cmp r15, 1
    je %%no_abort
    lea rdi, [str_mismatch]
    mov rsi, str_mismatch_len
    call print_str
    mov rax, 60
    mov rdi, 1
    syscall
%%no_abort:
%endmacro

section .data
str_JRuby:      db "JRuby"
str_connected:  db "CLIENT(ASM): connected to ", 0
str_connected_len: equ $-str_connected-1
str_nl:         db 10
str_result_pre: db "CLIENT(ASM): seq="
str_result_pre_len: equ $-str_result_pre
str_sent:       db " sent="
str_sent_len:   equ $-str_sent
str_reply:      db "B reply="
str_reply_len:  equ $-str_reply
str_bmatch:     db "B match="
str_bmatch_len: equ $-str_bmatch
str_true:       db "true"
str_true_len:   equ $-str_true
str_false:      db "FALSE"
str_false_len:  equ $-str_false
str_seq0: db "0"
str_seq1: db "1"
str_seq2: db "2"
str_mismatch: db "MISMATCH detected, aborting", 10
str_mismatch_len: equ $-str_mismatch
str_done: db "CLIENT(ASM): all messages round-tripped correctly", 10
str_done_len: equ $-str_done

section .bss
sockaddr_un: resb 110
reply_len_buf: resb 4
reply_buf: resb 16384
udec_buf: resb 24

section .text
_start:
    ; --- build sockaddr_un from argv[1] and connect ---
    lea rdi, [sockaddr_un]
    xor rax, rax
    mov rcx, 110/8
    rep stosq
    mov word [sockaddr_un], 1          ; AF_UNIX

    mov rsi, [rsp+16]                  ; argv[1]
    lea rdi, [sockaddr_un+2]
.copy_path:
    mov al, [rsi]
    mov [rdi], al
    inc rsi
    inc rdi
    test al, al
    jnz .copy_path

    mov rax, 41                        ; socket(AF_UNIX, SOCK_STREAM, 0)
    mov rdi, 1
    mov rsi, 1
    xor rdx, rdx
    syscall
    mov r12, rax                       ; r12 = fd, kept for the whole program

    mov rax, 42                        ; connect
    mov rdi, r12
    lea rsi, [sockaddr_un]
    mov rdx, 110
    syscall
    cmp rax, 0
    jl .exit_fail

    lea rdi, [str_connected]
    mov rsi, str_connected_len
    call print_str
    mov rsi, [rsp+16]
    call print_cstr
    lea rdi, [str_nl]
    mov rsi, 1
    call print_str

    ; --- message 0 ---
    TEST_MESSAGE out_frame0, out_frame0_len, expected_msg0, expected_msg0_len, 0, str_seq0

    ; --- message 1 ---
    TEST_MESSAGE out_frame1, out_frame1_len, expected_msg1, expected_msg1_len, 1, str_seq1

    ; --- message 2 (large, exercises str16) ---
    TEST_MESSAGE out_frame2, out_frame2_len, expected_msg2, expected_msg2_len, 2, str_seq2

    lea rdi, [str_done]
    mov rsi, str_done_len
    call print_str

    mov rax, 60
    xor rdi, rdi
    syscall

.exit_fail:
    mov rax, 60
    mov rdi, 1
    syscall

; ---- helpers ----

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

; print_cstr(rsi=ptr to NUL-terminated string)
print_cstr:
    mov r10, rsi            ; save original ptr
    mov rdi, rsi
.len_loop:
    cmp byte [rdi], 0
    je .len_done
    inc rdi
    jmp .len_loop
.len_done:
    sub rdi, r10             ; rdi = len
    mov rdx, rdi             ; rdx = len (syscall arg)
    mov rsi, r10             ; rsi = ptr (syscall arg)
    mov rdi, 1               ; fd = 1
    mov rax, 1               ; write
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
