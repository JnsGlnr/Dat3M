
typedef enum {
  __C26_op_add,
  __C26_op_sub,
  __C26_op_and,
  __C26_op_or,
  __C26_op_xor,
  __C26_op_min,
  __C26_op_max,
} __C26_op;

#define __C26__DECLARE_ATOMIC_OP(type) \
    extern void __C26_atomic_op_##type(_Atomic(type)* ptr, type value, __C26_op, memory_order);

__C26__DECLARE_ATOMIC_OP(short)
__C26__DECLARE_ATOMIC_OP(int)
__C26__DECLARE_ATOMIC_OP(long)

#define __SELECT(p) _Generic((p),               \
    _Atomic(short)* : __C26_atomic_op_short,    \
    _Atomic(int)*   : __C26_atomic_op_int,      \
    _Atomic(long)*  : __C26_atomic_op_long      \
)

// NOTE: for min/max we only have the signed version for now.

#define atomic_store_add_explicit(p, v, mo) __SELECT(p)(p, v, __C26_op_add, mo)
#define atomic_store_sub_explicit(p, v, mo) __SELECT(p)(p, v, __C26_op_sub, mo)
#define atomic_store_and_explicit(p, v, mo) __SELECT(p)(p, v, __C26_op_and, mo)
#define atomic_store_or_explicit(p, v, mo)  __SELECT(p)(p, v, __C26_op_or, mo)
#define atomic_store_xor_explicit(p, v, mo) __SELECT(p)(p, v, __C26_op_xor, mo)
#define atomic_store_min_explicit(p, v, mo) __SELECT(p)(p, v, __C26_op_min, mo)
#define atomic_store_max_explicit(p, v, mo) __SELECT(p)(p, v, __C26_op_max, mo)

#define atomic_store_add(p, v) __SELECT(p)(p, v, __C26_op_add, memory_order_seq_cst)
#define atomic_store_sub(p, v) __SELECT(p)(p, v, __C26_op_sub, memory_order_seq_cst)
#define atomic_store_and(p, v) __SELECT(p)(p, v, __C26_op_and, memory_order_seq_cst)
#define atomic_store_or(p, v)  __SELECT(p)(p, v, __C26_op_or,  memory_order_seq_cst)
#define atomic_store_xor(p, v) __SELECT(p)(p, v, __C26_op_xor, memory_order_seq_cst)
#define atomic_store_min(p, v) __SELECT(p)(p, v, __C26_op_min, memory_order_seq_cst)
#define atomic_store_max(p, v) __SELECT(p)(p, v, __C26_op_max, memory_order_seq_cst)
