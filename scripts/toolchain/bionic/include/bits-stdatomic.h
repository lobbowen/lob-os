
#pragma once

#if defined(__cplusplus) && __cplusplus >= 201103L && __has_include(<atomic>)
# if __has_feature(cxx_atomic)
#  define _STDATOMIC_HAVE_ATOMIC
#  include <atomic>
#  undef _Atomic
#  define _Atomic(t) std::atomic<t>
  using std::atomic_is_lock_free;
  using std::atomic_init;
  using std::atomic_store;
  using std::atomic_store_explicit;
  using std::atomic_load;
  using std::atomic_load_explicit;
  using std::atomic_exchange;
  using std::atomic_exchange_explicit;
  using std::atomic_compare_exchange_strong;
  using std::atomic_compare_exchange_strong_explicit;
  using std::atomic_compare_exchange_weak;
  using std::atomic_compare_exchange_weak_explicit;
  using std::atomic_fetch_add;
  using std::atomic_fetch_add_explicit;
  using std::atomic_fetch_sub;
  using std::atomic_fetch_sub_explicit;
  using std::atomic_fetch_or;
  using std::atomic_fetch_or_explicit;
  using std::atomic_fetch_xor;
  using std::atomic_fetch_xor_explicit;
  using std::atomic_thread_fence;
  using std::atomic_signal_fence;
# endif
#endif

#if !defined(_STDATOMIC_HAVE_ATOMIC)
#include <sys/cdefs.h>
#include <sys/types.h>
#include <stdbool.h>

#include <stddef.h>
#include <stdint.h>

#if defined(__BIONIC__)
# include <uchar.h>
#endif

#ifdef __GCC_ATOMIC_BOOL_LOCK_FREE
#define	ATOMIC_BOOL_LOCK_FREE		__GCC_ATOMIC_BOOL_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_CHAR_LOCK_FREE
#define	ATOMIC_CHAR_LOCK_FREE		__GCC_ATOMIC_CHAR_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_CHAR16_T_LOCK_FREE
#define	ATOMIC_CHAR16_T_LOCK_FREE	__GCC_ATOMIC_CHAR16_T_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_CHAR32_T_LOCK_FREE
#define	ATOMIC_CHAR32_T_LOCK_FREE	__GCC_ATOMIC_CHAR32_T_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_WCHAR_T_LOCK_FREE
#define	ATOMIC_WCHAR_T_LOCK_FREE	__GCC_ATOMIC_WCHAR_T_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_SHORT_LOCK_FREE
#define	ATOMIC_SHORT_LOCK_FREE		__GCC_ATOMIC_SHORT_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_INT_LOCK_FREE
#define	ATOMIC_INT_LOCK_FREE		__GCC_ATOMIC_INT_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_LONG_LOCK_FREE
#define	ATOMIC_LONG_LOCK_FREE		__GCC_ATOMIC_LONG_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_LLONG_LOCK_FREE
#define	ATOMIC_LLONG_LOCK_FREE		__GCC_ATOMIC_LLONG_LOCK_FREE
#endif
#ifdef __GCC_ATOMIC_POINTER_LOCK_FREE
#define	ATOMIC_POINTER_LOCK_FREE	__GCC_ATOMIC_POINTER_LOCK_FREE
#endif

#define	ATOMIC_VAR_INIT(value)		(value)
#define	atomic_init(obj, value)		__c11_atomic_init(obj, value)

#ifndef __ATOMIC_RELAXED
#define __ATOMIC_RELAXED		0
#endif
#ifndef __ATOMIC_CONSUME
#define __ATOMIC_CONSUME		1
#endif
#ifndef __ATOMIC_ACQUIRE
#define __ATOMIC_ACQUIRE		2
#endif
#ifndef __ATOMIC_RELEASE
#define __ATOMIC_RELEASE		3
#endif
#ifndef __ATOMIC_ACQ_REL
#define __ATOMIC_ACQ_REL		4
#endif
#ifndef __ATOMIC_SEQ_CST
#define __ATOMIC_SEQ_CST		5
#endif

typedef enum {
	memory_order_relaxed = __ATOMIC_RELAXED,
	memory_order_consume = __ATOMIC_CONSUME,
	memory_order_acquire = __ATOMIC_ACQUIRE,
	memory_order_release = __ATOMIC_RELEASE,
	memory_order_acq_rel = __ATOMIC_ACQ_REL,
	memory_order_seq_cst = __ATOMIC_SEQ_CST
} memory_order;

static __inline void atomic_thread_fence(memory_order __order __attribute__((__unused__))) {
	__c11_atomic_thread_fence(__order);
}

static __inline void atomic_signal_fence(memory_order __order __attribute__((__unused__))) {
	__c11_atomic_signal_fence(__order);
}

#define	atomic_is_lock_free(obj) __c11_atomic_is_lock_free(sizeof(*(obj)))

typedef _Atomic(bool)			atomic_bool;
typedef _Atomic(char)			atomic_char;
typedef _Atomic(signed char)		atomic_schar;
typedef _Atomic(unsigned char)		atomic_uchar;
typedef _Atomic(short)			atomic_short;
typedef _Atomic(unsigned short)		atomic_ushort;
typedef _Atomic(int)			atomic_int;
typedef _Atomic(unsigned int)		atomic_uint;
typedef _Atomic(long)			atomic_long;
typedef _Atomic(unsigned long)		atomic_ulong;
typedef _Atomic(long long)		atomic_llong;
typedef _Atomic(unsigned long long)	atomic_ullong;
#if defined(__BIONIC__) || (defined(__cplusplus) && __cplusplus >= 201103L)
  typedef _Atomic(char16_t)		atomic_char16_t;
  typedef _Atomic(char32_t)		atomic_char32_t;
#endif
typedef _Atomic(wchar_t)		atomic_wchar_t;
typedef _Atomic(int_least8_t)		atomic_int_least8_t;
typedef _Atomic(uint_least8_t)	atomic_uint_least8_t;
typedef _Atomic(int_least16_t)	atomic_int_least16_t;
typedef _Atomic(uint_least16_t)	atomic_uint_least16_t;
typedef _Atomic(int_least32_t)	atomic_int_least32_t;
typedef _Atomic(uint_least32_t)	atomic_uint_least32_t;
typedef _Atomic(int_least64_t)	atomic_int_least64_t;
typedef _Atomic(uint_least64_t)	atomic_uint_least64_t;
typedef _Atomic(int_fast8_t)		atomic_int_fast8_t;
typedef _Atomic(uint_fast8_t)		atomic_uint_fast8_t;
typedef _Atomic(int_fast16_t)		atomic_int_fast16_t;
typedef _Atomic(uint_fast16_t)	atomic_uint_fast16_t;
typedef _Atomic(int_fast32_t)		atomic_int_fast32_t;
typedef _Atomic(uint_fast32_t)	atomic_uint_fast32_t;
typedef _Atomic(int_fast64_t)		atomic_int_fast64_t;
typedef _Atomic(uint_fast64_t)	atomic_uint_fast64_t;
typedef _Atomic(intptr_t)		atomic_intptr_t;
typedef _Atomic(uintptr_t)		atomic_uintptr_t;
typedef _Atomic(size_t)		atomic_size_t;
typedef _Atomic(ptrdiff_t)		atomic_ptrdiff_t;
typedef _Atomic(intmax_t)		atomic_intmax_t;
typedef _Atomic(uintmax_t)		atomic_uintmax_t;

#define	atomic_compare_exchange_strong_explicit(object, expected,	\
    desired, success, failure)						\
	__c11_atomic_compare_exchange_strong(object, expected, desired,	\
	    success, failure)
#define	atomic_compare_exchange_weak_explicit(object, expected,		\
    desired, success, failure)						\
	__c11_atomic_compare_exchange_weak(object, expected, desired,	\
	    success, failure)
#define	atomic_exchange_explicit(object, desired, order)		\
	__c11_atomic_exchange(object, desired, order)
#define	atomic_fetch_add_explicit(object, operand, order)		\
	__c11_atomic_fetch_add(object, operand, order)
#define	atomic_fetch_and_explicit(object, operand, order)		\
	__c11_atomic_fetch_and(object, operand, order)
#define	atomic_fetch_or_explicit(object, operand, order)		\
	__c11_atomic_fetch_or(object, operand, order)
#define	atomic_fetch_sub_explicit(object, operand, order)		\
	__c11_atomic_fetch_sub(object, operand, order)
#define	atomic_fetch_xor_explicit(object, operand, order)		\
	__c11_atomic_fetch_xor(object, operand, order)
#define	atomic_load_explicit(object, order)				\
	__c11_atomic_load(object, order)
#define	atomic_store_explicit(object, desired, order)			\
	__c11_atomic_store(object, desired, order)

#define	atomic_compare_exchange_strong(object, expected, desired)	\
	atomic_compare_exchange_strong_explicit(object, expected,	\
	    desired, memory_order_seq_cst, memory_order_seq_cst)
#define	atomic_compare_exchange_weak(object, expected, desired)		\
	atomic_compare_exchange_weak_explicit(object, expected,		\
	    desired, memory_order_seq_cst, memory_order_seq_cst)
#define	atomic_exchange(object, desired)				\
	atomic_exchange_explicit(object, desired, memory_order_seq_cst)
#define	atomic_fetch_add(object, operand)				\
	atomic_fetch_add_explicit(object, operand, memory_order_seq_cst)
#define	atomic_fetch_and(object, operand)				\
	atomic_fetch_and_explicit(object, operand, memory_order_seq_cst)
#define	atomic_fetch_or(object, operand)				\
	atomic_fetch_or_explicit(object, operand, memory_order_seq_cst)
#define	atomic_fetch_sub(object, operand)				\
	atomic_fetch_sub_explicit(object, operand, memory_order_seq_cst)
#define	atomic_fetch_xor(object, operand)				\
	atomic_fetch_xor_explicit(object, operand, memory_order_seq_cst)
#define	atomic_load(object)						\
	atomic_load_explicit(object, memory_order_seq_cst)
#define	atomic_store(object, desired)					\
	atomic_store_explicit(object, desired, memory_order_seq_cst)

typedef struct {
	atomic_bool	__flag;
} atomic_flag;

#define	ATOMIC_FLAG_INIT		{ ATOMIC_VAR_INIT(false) }

static __inline bool atomic_flag_test_and_set_explicit(volatile atomic_flag * _Nonnull __object, memory_order __order) {
	return (atomic_exchange_explicit(&__object->__flag, 1, __order));
}

static __inline void atomic_flag_clear_explicit(volatile atomic_flag * _Nonnull __object, memory_order __order) {
	atomic_store_explicit(&__object->__flag, 0, __order);
}

static __inline bool atomic_flag_test_and_set(volatile atomic_flag * _Nonnull __object) {
	return (atomic_flag_test_and_set_explicit(__object, memory_order_seq_cst));
}

static __inline void atomic_flag_clear(volatile atomic_flag * _Nonnull __object) {
	atomic_flag_clear_explicit(__object, memory_order_seq_cst);
}

#endif
