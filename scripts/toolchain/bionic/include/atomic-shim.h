#pragma once
#ifdef atomic_is_lock_free
#undef atomic_is_lock_free
#endif
#ifdef atomic_init
#undef atomic_init
#endif
#ifdef atomic_store
#undef atomic_store
#endif
#ifdef atomic_store_explicit
#undef atomic_store_explicit
#endif
#ifdef atomic_load
#undef atomic_load
#endif
#ifdef atomic_load_explicit
#undef atomic_load_explicit
#endif
#ifdef atomic_exchange
#undef atomic_exchange
#endif
#ifdef atomic_exchange_explicit
#undef atomic_exchange_explicit
#endif
#ifdef atomic_compare_exchange_strong
#undef atomic_compare_exchange_strong
#endif
#ifdef atomic_compare_exchange_strong_explicit
#undef atomic_compare_exchange_strong_explicit
#endif
#ifdef atomic_compare_exchange_weak
#undef atomic_compare_exchange_weak
#endif
#ifdef atomic_compare_exchange_weak_explicit
#undef atomic_compare_exchange_weak_explicit
#endif
#ifdef atomic_fetch_add
#undef atomic_fetch_add
#endif
#ifdef atomic_fetch_add_explicit
#undef atomic_fetch_add_explicit
#endif
#ifdef atomic_fetch_sub
#undef atomic_fetch_sub
#endif
#ifdef atomic_fetch_sub_explicit
#undef atomic_fetch_sub_explicit
#endif
#ifdef atomic_fetch_or
#undef atomic_fetch_or
#endif
#ifdef atomic_fetch_or_explicit
#undef atomic_fetch_or_explicit
#endif
#ifdef atomic_fetch_xor
#undef atomic_fetch_xor
#endif
#ifdef atomic_fetch_xor_explicit
#undef atomic_fetch_xor_explicit
#endif
#ifdef atomic_thread_fence
#undef atomic_thread_fence
#endif
#ifdef atomic_signal_fence
#undef atomic_signal_fence
#endif
#include <atomic>
