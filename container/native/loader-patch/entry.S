    .text
    .globl _start
    .org 0x23850
_start:
    bti     j
    stp     x0, x1, [sp, #-16]!
    stp     x2, x3, [sp, #-16]!
    stp     x8, x9, [sp, #-16]!
    sub     sp, sp, #32
    mov     x0, sp
    mov     x1, #0
    stp     x1, x1, [x0, #0]
    stp     x1, x1, [x0, #16]
    adr     x1, sigsys_handler
    str     x1, [x0, #0]
    movz    x1, #4
    movk    x1, #0x1000, lsl #16
    str     x1, [x0, #8]
    mov     x8, #134
    mov     x0, #31
    mov     x1, sp
    mov     x2, #0
    mov     x3, #0
    mov     x4, #8
    svc     #0
    add     sp, sp, #32
    ldp     x8, x9, [sp], #16
    ldp     x2, x3, [sp], #16
    ldp     x0, x1, [sp], #16
    /* replay overwritten entry instructions */
    mov     x29, #0
    mov     x30, #0
    mov     x0, sp
    /* direct branch (no BTI check on target) to original 0x1b290 */
    b       0x1b290
    .balign 16
sigsys_handler:
    /* handle every SIGSYS unconditionally (Android seccomp si_code may differ) */
    ldr     x3, [x1, #24]
    movn    x4, #37
    mov     x6, #184
    str     x4, [x2, x6]
    mov     x6, #440
    ldr     x5, [x2, x6]
    cmp     x5, #4
    b.lt    1f
    ldr     w7, [x5]
    sub     x9, x5, #4
    ldr     w8, [x9]
    movz    w10, #1
    movk    w10, #0xffe0, lsl #16
    and     w7, w7, w10
    and     w8, w8, w10
    movz    w11, #1
    movk    w11, #0xd400, lsl #16
    cmp     w7, w11
    b.ne    1f
    cmp     w8, w11
    b.eq    1f
    add     x5, x5, #4
    str     x5, [x2, x6]
1:  ret
