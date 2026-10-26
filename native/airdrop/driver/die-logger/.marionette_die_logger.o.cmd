cmd_/Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.o := clang -Wp,-MMD,/Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/.marionette_die_logger.o.d -nostdinc -isystem /work/clang/lib64/clang/14.0.7/include -I/work/kernel/arch/arm64/include -I./arch/arm64/include/generated -I/work/kernel/include -I./include -I/work/kernel/arch/arm64/include/uapi -I./arch/arm64/include/generated/uapi -I/work/kernel/include/uapi -I./include/generated/uapi -include /work/kernel/include/linux/compiler-version.h -include /work/kernel/include/linux/kconfig.h -include /work/kernel/include/linux/compiler_types.h -D__KERNEL__ -mlittle-endian -DKASAN_SHADOW_SCALE_SHIFT= -Qunused-arguments -fmacro-prefix-map=/work/kernel/= -Wall -Wundef -Werror=strict-prototypes -Wno-trigraphs -fno-strict-aliasing -fno-common -fshort-wchar -fno-PIE -Werror=implicit-function-declaration -Werror=implicit-int -Werror=return-type -Wno-format-security -std=gnu89 --target=aarch64-linux-gnu -fintegrated-as -Werror=unknown-warning-option -Werror=ignored-optimization-argument -mgeneral-regs-only -DCONFIG_CC_HAS_K_CONSTRAINT=1 -Wno-psabi -fno-asynchronous-unwind-tables -fno-unwind-tables -mbranch-protection=pac-ret+leaf+bti -Wa,-march=armv8.5-a -DARM64_ASM_ARCH='"armv8.5-a"' -ffixed-x18 -DKASAN_SHADOW_SCALE_SHIFT= -fno-delete-null-pointer-checks -Wno-frame-address -Wno-address-of-packed-member -O2 -Wframe-larger-than=2048 -fstack-protector-strong -Werror -Wno-gnu -mno-global-merge -Wno-unused-but-set-variable -Wno-unused-const-variable -fno-omit-frame-pointer -fno-optimize-sibling-calls -ftrivial-auto-var-init=zero -enable-trivial-auto-var-init-zero-knowing-it-will-be-removed-from-clang -fno-stack-clash-protection -g -gdwarf-4 -fsanitize=shadow-call-stack -fno-lto -flto -fvisibility=default -fsanitize=cfi -fsanitize-cfi-cross-dso -fno-sanitize-cfi-canonical-jump-tables -fno-sanitize-trap=cfi -fno-sanitize-blacklist -Wdeclaration-after-statement -Wvla -Wno-pointer-sign -Wno-array-bounds -fno-strict-overflow -fno-stack-check -Werror=date-time -Werror=incompatible-pointer-types -Wno-initializer-overrides -Wno-format -Wno-sign-compare -Wno-format-zero-length -Wno-pointer-to-enum-cast -Wno-tautological-constant-out-of-range-compare -Wno-unaligned-access -mstack-protector-guard=sysreg -mstack-protector-guard-reg=sp_el0 -mstack-protector-guard-offset=1504  -fsanitize=array-bounds -fsanitize=local-bounds -fsanitize-undefined-trap-on-error  -DMODULE  -DKBUILD_BASENAME='"marionette_die_logger"' -DKBUILD_MODNAME='"marionette_die_logger"' -D__KBUILD_MODNAME=kmod_marionette_die_logger -c -o /Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.o /Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.c

source_/Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.o := /Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.c

deps_/Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.o := \
  /work/kernel/include/linux/compiler-version.h \
    $(wildcard include/config/CC_VERSION_TEXT) \
  /work/kernel/include/linux/kconfig.h \
    $(wildcard include/config/CPU_BIG_ENDIAN) \
    $(wildcard include/config/BOOGER) \
    $(wildcard include/config/FOO) \
  /work/kernel/include/linux/compiler_types.h \
    $(wildcard include/config/HAVE_ARCH_COMPILER_H) \
    $(wildcard include/config/CC_HAS_ASM_INLINE) \
  /work/kernel/include/linux/compiler_attributes.h \
  /work/kernel/include/linux/compiler-clang.h \
    $(wildcard include/config/ARCH_USE_BUILTIN_BSWAP) \
  /work/kernel/arch/arm64/include/asm/compiler.h \
    $(wildcard include/config/CFI_CLANG) \
  /work/kernel/include/linux/module.h \
    $(wildcard include/config/MODULES) \
    $(wildcard include/config/SYSFS) \
    $(wildcard include/config/MODULES_TREE_LOOKUP) \
    $(wildcard include/config/LIVEPATCH) \
    $(wildcard include/config/STACKTRACE_BUILD_ID) \
    $(wildcard include/config/GENERIC_BUG) \
    $(wildcard include/config/KALLSYMS) \
    $(wildcard include/config/SMP) \
    $(wildcard include/config/TRACEPOINTS) \
    $(wildcard include/config/TREE_SRCU) \
    $(wildcard include/config/BPF_EVENTS) \
    $(wildcard include/config/DEBUG_INFO_BTF_MODULES) \
    $(wildcard include/config/JUMP_LABEL) \
    $(wildcard include/config/TRACING) \
    $(wildcard include/config/EVENT_TRACING) \
    $(wildcard include/config/FTRACE_MCOUNT_RECORD) \
    $(wildcard include/config/KPROBES) \
    $(wildcard include/config/HAVE_STATIC_CALL_INLINE) \
    $(wildcard include/config/PRINTK_INDEX) \
    $(wildcard include/config/MODULE_UNLOAD) \
    $(wildcard include/config/CONSTRUCTORS) \
    $(wildcard include/config/FUNCTION_ERROR_INJECTION) \
    $(wildcard include/config/RETPOLINE) \
    $(wildcard include/config/MODULE_SIG) \
  /work/kernel/include/linux/list.h \
    $(wildcard include/config/DEBUG_LIST) \
  /work/kernel/include/linux/types.h \
    $(wildcard include/config/HAVE_UID16) \
    $(wildcard include/config/UID16) \
    $(wildcard include/config/ARCH_DMA_ADDR_T_64BIT) \
    $(wildcard include/config/PHYS_ADDR_T_64BIT) \
    $(wildcard include/config/64BIT) \
    $(wildcard include/config/ARCH_32BIT_USTAT_F_TINODE) \
  /work/kernel/include/uapi/linux/types.h \
  arch/arm64/include/generated/uapi/asm/types.h \
  /work/kernel/include/uapi/asm-generic/types.h \
  /work/kernel/include/asm-generic/int-ll64.h \
  /work/kernel/include/uapi/asm-generic/int-ll64.h \
  /work/kernel/arch/arm64/include/uapi/asm/bitsperlong.h \
  /work/kernel/include/asm-generic/bitsperlong.h \
  /work/kernel/include/uapi/asm-generic/bitsperlong.h \
  /work/kernel/include/uapi/linux/posix_types.h \
  /work/kernel/include/linux/stddef.h \
  /work/kernel/include/uapi/linux/stddef.h \
  /work/kernel/arch/arm64/include/uapi/asm/posix_types.h \
  /work/kernel/include/uapi/asm-generic/posix_types.h \
  /work/kernel/include/linux/poison.h \
    $(wildcard include/config/ILLEGAL_POINTER_VALUE) \
  /work/kernel/include/linux/const.h \
  /work/kernel/include/vdso/const.h \
  /work/kernel/include/uapi/linux/const.h \
  /work/kernel/include/linux/kernel.h \
    $(wildcard include/config/PREEMPT_VOLUNTARY) \
    $(wildcard include/config/PREEMPT_DYNAMIC) \
    $(wildcard include/config/PREEMPT_) \
    $(wildcard include/config/DEBUG_ATOMIC_SLEEP) \
    $(wildcard include/config/MMU) \
    $(wildcard include/config/PROVE_LOCKING) \
  /work/kernel/include/linux/stdarg.h \
  /work/kernel/include/linux/align.h \
  /work/kernel/include/linux/limits.h \
  /work/kernel/include/uapi/linux/limits.h \
  /work/kernel/include/vdso/limits.h \
  /work/kernel/include/linux/linkage.h \
    $(wildcard include/config/ARCH_USE_SYM_ANNOTATIONS) \
  /work/kernel/include/linux/stringify.h \
  /work/kernel/include/linux/export.h \
    $(wildcard include/config/MODVERSIONS) \
    $(wildcard include/config/MODULE_REL_CRCS) \
    $(wildcard include/config/HAVE_ARCH_PREL32_RELOCATIONS) \
    $(wildcard include/config/TRIM_UNUSED_KSYMS) \
  /work/kernel/include/linux/compiler.h \
    $(wildcard include/config/TRACE_BRANCH_PROFILING) \
    $(wildcard include/config/PROFILE_ALL_BRANCHES) \
    $(wildcard include/config/STACK_VALIDATION) \
  /work/kernel/arch/arm64/include/asm/rwonce.h \
    $(wildcard include/config/LTO) \
    $(wildcard include/config/AS_HAS_LDAPR) \
  /work/kernel/arch/arm64/include/asm/alternative-macros.h \
  arch/arm64/include/generated/asm/cpucaps.h \
  /work/kernel/arch/arm64/include/asm/insn-def.h \
  /work/kernel/include/asm-generic/rwonce.h \
  /work/kernel/include/linux/kasan-checks.h \
    $(wildcard include/config/KASAN_GENERIC) \
    $(wildcard include/config/KASAN_SW_TAGS) \
  /work/kernel/include/linux/kcsan-checks.h \
    $(wildcard include/config/KCSAN) \
    $(wildcard include/config/KCSAN_IGNORE_ATOMICS) \
  /work/kernel/arch/arm64/include/asm/linkage.h \
    $(wildcard include/config/ARM64_BTI_KERNEL) \
  /work/kernel/include/linux/bitops.h \
  /work/kernel/include/linux/bits.h \
  /work/kernel/include/vdso/bits.h \
  /work/kernel/include/linux/build_bug.h \
  /work/kernel/include/linux/typecheck.h \
  /work/kernel/include/uapi/linux/kernel.h \
  /work/kernel/include/uapi/linux/sysinfo.h \
  /work/kernel/arch/arm64/include/asm/bitops.h \
  /work/kernel/include/asm-generic/bitops/builtin-__ffs.h \
  /work/kernel/include/asm-generic/bitops/builtin-ffs.h \
  /work/kernel/include/asm-generic/bitops/builtin-__fls.h \
  /work/kernel/include/asm-generic/bitops/builtin-fls.h \
  /work/kernel/include/asm-generic/bitops/ffz.h \
  /work/kernel/include/asm-generic/bitops/fls64.h \
  /work/kernel/include/asm-generic/bitops/find.h \
    $(wildcard include/config/GENERIC_FIND_FIRST_BIT) \
  /work/kernel/include/asm-generic/bitops/sched.h \
  /work/kernel/include/asm-generic/bitops/hweight.h \
  /work/kernel/include/asm-generic/bitops/arch_hweight.h \
  /work/kernel/include/asm-generic/bitops/const_hweight.h \
  /work/kernel/include/asm-generic/bitops/atomic.h \
  /work/kernel/include/linux/atomic.h \
  /work/kernel/arch/arm64/include/asm/atomic.h \
  /work/kernel/arch/arm64/include/asm/barrier.h \
    $(wildcard include/config/ARM64_PSEUDO_NMI) \
  /work/kernel/include/asm-generic/barrier.h \
  /work/kernel/arch/arm64/include/asm/cmpxchg.h \
  /work/kernel/arch/arm64/include/asm/lse.h \
    $(wildcard include/config/ARM64_LSE_ATOMICS) \
  /work/kernel/arch/arm64/include/asm/atomic_ll_sc.h \
    $(wildcard include/config/CC_HAS_K_CONSTRAINT) \
  /work/kernel/include/linux/jump_label.h \
    $(wildcard include/config/HAVE_ARCH_JUMP_LABEL_RELATIVE) \
  /work/kernel/arch/arm64/include/asm/jump_label.h \
  /work/kernel/arch/arm64/include/asm/insn.h \
  /work/kernel/arch/arm64/include/asm/alternative.h \
  /work/kernel/include/linux/init.h \
    $(wildcard include/config/STRICT_KERNEL_RWX) \
    $(wildcard include/config/STRICT_MODULE_RWX) \
    $(wildcard include/config/LTO_CLANG) \
  /work/kernel/arch/arm64/include/asm/atomic_lse.h \
  /work/kernel/include/linux/atomic/atomic-arch-fallback.h \
    $(wildcard include/config/GENERIC_ATOMIC64) \
  /work/kernel/include/linux/atomic/atomic-long.h \
  /work/kernel/include/linux/atomic/atomic-instrumented.h \
  /work/kernel/include/linux/instrumented.h \
  /work/kernel/include/asm-generic/bitops/instrumented-atomic.h \
  /work/kernel/include/asm-generic/bitops/lock.h \
  /work/kernel/include/asm-generic/bitops/instrumented-lock.h \
  /work/kernel/include/asm-generic/bitops/non-atomic.h \
  /work/kernel/include/asm-generic/bitops/le.h \
  /work/kernel/arch/arm64/include/uapi/asm/byteorder.h \
  /work/kernel/include/linux/byteorder/little_endian.h \
  /work/kernel/include/uapi/linux/byteorder/little_endian.h \
  /work/kernel/include/linux/swab.h \
  /work/kernel/include/uapi/linux/swab.h \
  arch/arm64/include/generated/uapi/asm/swab.h \
  /work/kernel/include/uapi/asm-generic/swab.h \
  /work/kernel/include/linux/byteorder/generic.h \
  /work/kernel/include/asm-generic/bitops/ext2-atomic-setbit.h \
  /work/kernel/include/linux/kstrtox.h \
  /work/kernel/include/linux/log2.h \
    $(wildcard include/config/ARCH_HAS_ILOG2_U32) \
    $(wildcard include/config/ARCH_HAS_ILOG2_U64) \
  /work/kernel/include/linux/math.h \
  arch/arm64/include/generated/asm/div64.h \
  /work/kernel/include/asm-generic/div64.h \
  /work/kernel/include/linux/minmax.h \
  /work/kernel/include/linux/panic.h \
    $(wildcard include/config/PANIC_TIMEOUT) \
  /work/kernel/include/linux/printk.h \
    $(wildcard include/config/MESSAGE_LOGLEVEL_DEFAULT) \
    $(wildcard include/config/CONSOLE_LOGLEVEL_DEFAULT) \
    $(wildcard include/config/CONSOLE_LOGLEVEL_QUIET) \
    $(wildcard include/config/EARLY_PRINTK) \
    $(wildcard include/config/PRINTK) \
    $(wildcard include/config/DYNAMIC_DEBUG) \
    $(wildcard include/config/DYNAMIC_DEBUG_CORE) \
  /work/kernel/include/linux/kern_levels.h \
  /work/kernel/include/linux/cache.h \
    $(wildcard include/config/ARCH_HAS_CACHE_LINE_SIZE) \
  /work/kernel/arch/arm64/include/asm/cache.h \
    $(wildcard include/config/KASAN_HW_TAGS) \
  /work/kernel/arch/arm64/include/asm/cputype.h \
  /work/kernel/arch/arm64/include/asm/sysreg.h \
    $(wildcard include/config/BROKEN_GAS_INST) \
    $(wildcard include/config/ARM64_PA_BITS_52) \
    $(wildcard include/config/ARM64_4K_PAGES) \
    $(wildcard include/config/ARM64_16K_PAGES) \
    $(wildcard include/config/ARM64_64K_PAGES) \
  /work/kernel/include/linux/kasan-tags.h \
  /work/kernel/include/linux/ratelimit_types.h \
  /work/kernel/include/uapi/linux/param.h \
  /work/kernel/arch/arm64/include/uapi/asm/param.h \
  /work/kernel/include/asm-generic/param.h \
    $(wildcard include/config/HZ) \
  /work/kernel/include/uapi/asm-generic/param.h \
  /work/kernel/include/linux/spinlock_types.h \
    $(wildcard include/config/PREEMPT_RT) \
    $(wildcard include/config/DEBUG_LOCK_ALLOC) \
  /work/kernel/include/linux/spinlock_types_raw.h \
    $(wildcard include/config/DEBUG_SPINLOCK) \
  /work/kernel/arch/arm64/include/asm/spinlock_types.h \
  /work/kernel/include/asm-generic/qspinlock_types.h \
    $(wildcard include/config/NR_CPUS) \
  /work/kernel/include/asm-generic/qrwlock_types.h \
  /work/kernel/include/linux/lockdep_types.h \
    $(wildcard include/config/PROVE_RAW_LOCK_NESTING) \
    $(wildcard include/config/PREEMPT_LOCK) \
    $(wildcard include/config/LOCKDEP) \
    $(wildcard include/config/LOCK_STAT) \
  /work/kernel/include/linux/rwlock_types.h \
  /work/kernel/include/linux/once_lite.h \
  /work/kernel/include/linux/static_call_types.h \
    $(wildcard include/config/HAVE_STATIC_CALL) \
  /work/kernel/include/linux/stat.h \
  /work/kernel/arch/arm64/include/asm/stat.h \
    $(wildcard include/config/COMPAT) \
  arch/arm64/include/generated/uapi/asm/stat.h \
  /work/kernel/include/uapi/asm-generic/stat.h \
  /work/kernel/include/linux/time.h \
    $(wildcard include/config/POSIX_TIMERS) \
  /work/kernel/include/linux/math64.h \
    $(wildcard include/config/ARCH_SUPPORTS_INT128) \
  /work/kernel/include/vdso/math64.h \
  /work/kernel/include/linux/time64.h \
  /work/kernel/include/vdso/time64.h \
  /work/kernel/include/uapi/linux/time.h \
  /work/kernel/include/uapi/linux/time_types.h \
  /work/kernel/include/linux/time32.h \
  /work/kernel/include/linux/timex.h \
  /work/kernel/include/uapi/linux/timex.h \
  /work/kernel/arch/arm64/include/asm/timex.h \
  /work/kernel/arch/arm64/include/asm/arch_timer.h \
    $(wildcard include/config/ARM_ARCH_TIMER_OOL_WORKAROUND) \
  /work/kernel/arch/arm64/include/asm/hwcap.h \
  /work/kernel/arch/arm64/include/uapi/asm/hwcap.h \
  /work/kernel/arch/arm64/include/asm/cpufeature.h \
    $(wildcard include/config/ARM64_PAN) \
    $(wildcard include/config/ARM64_SW_TTBR0_PAN) \
    $(wildcard include/config/ARM64_SVE) \
    $(wildcard include/config/ARM64_CNP) \
    $(wildcard include/config/ARM64_PTR_AUTH) \
    $(wildcard include/config/ARM64_MTE) \
    $(wildcard include/config/ARM64_DEBUG_PRIORITY_MASKING) \
    $(wildcard include/config/ARM64_BTI) \
    $(wildcard include/config/ARM64_TLB_RANGE) \
    $(wildcard include/config/ARM64_PA_BITS) \
    $(wildcard include/config/ARM64_HW_AFDBM) \
    $(wildcard include/config/ARM64_AMU_EXTN) \
  /work/kernel/include/linux/bug.h \
    $(wildcard include/config/BUG_ON_DATA_CORRUPTION) \
  /work/kernel/arch/arm64/include/asm/bug.h \
  /work/kernel/arch/arm64/include/asm/asm-bug.h \
    $(wildcard include/config/DEBUG_BUGVERBOSE) \
  /work/kernel/arch/arm64/include/asm/brk-imm.h \
  /work/kernel/include/asm-generic/bug.h \
    $(wildcard include/config/BUG) \
    $(wildcard include/config/GENERIC_BUG_RELATIVE_POINTERS) \
  /work/kernel/include/linux/instrumentation.h \
    $(wildcard include/config/DEBUG_ENTRY) \
  /work/kernel/include/linux/smp.h \
    $(wildcard include/config/UP_LATE_INIT) \
    $(wildcard include/config/DEBUG_PREEMPT) \
  /work/kernel/include/linux/errno.h \
  /work/kernel/include/uapi/linux/errno.h \
  arch/arm64/include/generated/uapi/asm/errno.h \
  /work/kernel/include/uapi/asm-generic/errno.h \
  /work/kernel/include/uapi/asm-generic/errno-base.h \
  /work/kernel/include/linux/cpumask.h \
    $(wildcard include/config/CPUMASK_OFFSTACK) \
    $(wildcard include/config/HOTPLUG_CPU) \
    $(wildcard include/config/DEBUG_PER_CPU_MAPS) \
  /work/kernel/include/linux/threads.h \
    $(wildcard include/config/BASE_SMALL) \
  /work/kernel/include/linux/bitmap.h \
  /work/kernel/include/linux/string.h \
    $(wildcard include/config/BINARY_PRINTF) \
    $(wildcard include/config/FORTIFY_SOURCE) \
  /work/kernel/include/uapi/linux/string.h \
  /work/kernel/arch/arm64/include/asm/string.h \
    $(wildcard include/config/ARCH_HAS_UACCESS_FLUSHCACHE) \
  /work/kernel/include/linux/smp_types.h \
  /work/kernel/include/linux/llist.h \
    $(wildcard include/config/ARCH_HAVE_NMI_SAFE_CMPXCHG) \
  /work/kernel/include/linux/preempt.h \
    $(wildcard include/config/PREEMPT_COUNT) \
    $(wildcard include/config/TRACE_PREEMPT_TOGGLE) \
    $(wildcard include/config/PREEMPTION) \
    $(wildcard include/config/PREEMPT_NOTIFIERS) \
  /work/kernel/arch/arm64/include/asm/preempt.h \
  /work/kernel/include/linux/thread_info.h \
    $(wildcard include/config/THREAD_INFO_IN_TASK) \
    $(wildcard include/config/GENERIC_ENTRY) \
    $(wildcard include/config/HAVE_ARCH_WITHIN_STACK_FRAMES) \
    $(wildcard include/config/HARDENED_USERCOPY) \
  /work/kernel/include/linux/restart_block.h \
  /work/kernel/arch/arm64/include/asm/current.h \
  /work/kernel/arch/arm64/include/asm/thread_info.h \
    $(wildcard include/config/SHADOW_CALL_STACK) \
  /work/kernel/arch/arm64/include/asm/memory.h \
    $(wildcard include/config/ARM64_VA_BITS) \
    $(wildcard include/config/KASAN_SHADOW_OFFSET) \
    $(wildcard include/config/KASAN) \
    $(wildcard include/config/VMAP_STACK) \
    $(wildcard include/config/DEBUG_VIRTUAL) \
    $(wildcard include/config/EFI) \
    $(wildcard include/config/ARM_GIC_V3_ITS) \
  /work/kernel/include/linux/sizes.h \
  /work/kernel/arch/arm64/include/asm/page-def.h \
    $(wildcard include/config/ARM64_PAGE_SHIFT) \
  /work/kernel/include/linux/mmdebug.h \
    $(wildcard include/config/DEBUG_VM) \
    $(wildcard include/config/DEBUG_VM_PGFLAGS) \
  /work/kernel/include/asm-generic/memory_model.h \
    $(wildcard include/config/FLATMEM) \
    $(wildcard include/config/SPARSEMEM_VMEMMAP) \
    $(wildcard include/config/SPARSEMEM) \
  /work/kernel/include/linux/pfn.h \
  /work/kernel/arch/arm64/include/asm/stack_pointer.h \
  /work/kernel/arch/arm64/include/asm/smp.h \
    $(wildcard include/config/ARM64_ACPI_PARKING_PROTOCOL) \
  /work/kernel/arch/arm64/include/asm/percpu.h \
  /work/kernel/include/asm-generic/percpu.h \
    $(wildcard include/config/HAVE_SETUP_PER_CPU_AREA) \
  /work/kernel/include/linux/percpu-defs.h \
    $(wildcard include/config/DEBUG_FORCE_WEAK_PER_CPU) \
    $(wildcard include/config/AMD_MEM_ENCRYPT) \
  /work/kernel/include/clocksource/arm_arch_timer.h \
    $(wildcard include/config/ARM_ARCH_TIMER) \
  /work/kernel/include/linux/timecounter.h \
  /work/kernel/include/asm-generic/timex.h \
  /work/kernel/include/vdso/time32.h \
  /work/kernel/include/vdso/time.h \
  /work/kernel/arch/arm64/include/asm/compat.h \
  /work/kernel/include/asm-generic/compat.h \
    $(wildcard include/config/COMPAT_FOR_U64_ALIGNMENT) \
  /work/kernel/include/linux/sched.h \
    $(wildcard include/config/VIRT_CPU_ACCOUNTING_NATIVE) \
    $(wildcard include/config/SCHED_INFO) \
    $(wildcard include/config/SCHEDSTATS) \
    $(wildcard include/config/FAIR_GROUP_SCHED) \
    $(wildcard include/config/RT_GROUP_SCHED) \
    $(wildcard include/config/RT_MUTEXES) \
    $(wildcard include/config/UCLAMP_TASK) \
    $(wildcard include/config/UCLAMP_BUCKETS_COUNT) \
    $(wildcard include/config/KMAP_LOCAL) \
    $(wildcard include/config/SCHED_CORE) \
    $(wildcard include/config/CGROUP_SCHED) \
    $(wildcard include/config/BLK_DEV_IO_TRACE) \
    $(wildcard include/config/PREEMPT_RCU) \
    $(wildcard include/config/TASKS_RCU) \
    $(wildcard include/config/TASKS_TRACE_RCU) \
    $(wildcard include/config/PSI) \
    $(wildcard include/config/MEMCG) \
    $(wildcard include/config/LRU_GEN) \
    $(wildcard include/config/COMPAT_BRK) \
    $(wildcard include/config/CGROUPS) \
    $(wildcard include/config/BLK_CGROUP) \
    $(wildcard include/config/PAGE_OWNER) \
    $(wildcard include/config/EVENTFD) \
    $(wildcard include/config/STACKPROTECTOR) \
    $(wildcard include/config/ARCH_HAS_SCALED_CPUTIME) \
    $(wildcard include/config/CPU_FREQ_TIMES) \
    $(wildcard include/config/VIRT_CPU_ACCOUNTING_GEN) \
    $(wildcard include/config/NO_HZ_FULL) \
    $(wildcard include/config/POSIX_CPUTIMERS) \
    $(wildcard include/config/POSIX_CPU_TIMERS_TASK_WORK) \
    $(wildcard include/config/KEYS) \
    $(wildcard include/config/SYSVIPC) \
    $(wildcard include/config/DETECT_HUNG_TASK) \
    $(wildcard include/config/IO_URING) \
    $(wildcard include/config/AUDIT) \
    $(wildcard include/config/AUDITSYSCALL) \
    $(wildcard include/config/DEBUG_MUTEXES) \
    $(wildcard include/config/TRACE_IRQFLAGS) \
    $(wildcard include/config/UBSAN) \
    $(wildcard include/config/UBSAN_TRAP) \
    $(wildcard include/config/BLOCK) \
    $(wildcard include/config/COMPACTION) \
    $(wildcard include/config/TASK_XACCT) \
    $(wildcard include/config/CPUSETS) \
    $(wildcard include/config/X86_CPU_RESCTRL) \
    $(wildcard include/config/FUTEX) \
    $(wildcard include/config/PERF_EVENTS) \
    $(wildcard include/config/NUMA) \
    $(wildcard include/config/NUMA_BALANCING) \
    $(wildcard include/config/RSEQ) \
    $(wildcard include/config/TASK_DELAY_ACCT) \
    $(wildcard include/config/FAULT_INJECTION) \
    $(wildcard include/config/LATENCYTOP) \
    $(wildcard include/config/FUNCTION_GRAPH_TRACER) \
    $(wildcard include/config/KCOV) \
    $(wildcard include/config/UPROBES) \
    $(wildcard include/config/BCACHE) \
    $(wildcard include/config/SECURITY) \
    $(wildcard include/config/BPF_SYSCALL) \
    $(wildcard include/config/GCC_PLUGIN_STACKLEAK) \
    $(wildcard include/config/X86_MCE) \
    $(wildcard include/config/KRETPROBES) \
    $(wildcard include/config/ARCH_HAS_PARANOID_L1D_FLUSH) \
    $(wildcard include/config/RT_SOFTINT_OPTIMIZATION) \
    $(wildcard include/config/ARCH_TASK_STRUCT_ON_STACK) \
    $(wildcard include/config/DEBUG_RSEQ) \
  /work/kernel/include/uapi/linux/sched.h \
  /work/kernel/include/linux/pid.h \
  /work/kernel/include/linux/rculist.h \
    $(wildcard include/config/PROVE_RCU_LIST) \
  /work/kernel/include/linux/rcupdate.h \
    $(wildcard include/config/TINY_RCU) \
    $(wildcard include/config/TASKS_RCU_GENERIC) \
    $(wildcard include/config/RCU_STALL_COMMON) \
    $(wildcard include/config/RCU_NOCB_CPU) \
    $(wildcard include/config/TASKS_RUDE_RCU) \
    $(wildcard include/config/TREE_RCU) \
    $(wildcard include/config/DEBUG_OBJECTS_RCU_HEAD) \
    $(wildcard include/config/PROVE_RCU) \
    $(wildcard include/config/ARCH_WEAK_RELEASE_ACQUIRE) \
  /work/kernel/include/linux/irqflags.h \
    $(wildcard include/config/IRQSOFF_TRACER) \
    $(wildcard include/config/PREEMPT_TRACER) \
    $(wildcard include/config/DEBUG_IRQFLAGS) \
    $(wildcard include/config/TRACE_IRQFLAGS_SUPPORT) \
  /work/kernel/arch/arm64/include/asm/irqflags.h \
  /work/kernel/arch/arm64/include/asm/ptrace.h \
  /work/kernel/arch/arm64/include/uapi/asm/ptrace.h \
  /work/kernel/arch/arm64/include/uapi/asm/sve_context.h \
  /work/kernel/include/linux/bottom_half.h \
  /work/kernel/include/linux/lockdep.h \
    $(wildcard include/config/DEBUG_LOCKING_API_SELFTESTS) \
  /work/kernel/arch/arm64/include/asm/processor.h \
    $(wildcard include/config/KUSER_HELPERS) \
    $(wildcard include/config/ARM64_FORCE_52BIT) \
    $(wildcard include/config/HAVE_HW_BREAKPOINT) \
    $(wildcard include/config/ARM64_PTR_AUTH_KERNEL) \
    $(wildcard include/config/ARM64_TAGGED_ADDR_ABI) \
  /work/kernel/include/linux/android_vendor.h \
    $(wildcard include/config/ANDROID_VENDOR_OEM_DATA) \
  /work/kernel/include/linux/android_kabi.h \
    $(wildcard include/config/ANDROID_KABI_RESERVE) \
  /work/kernel/include/vdso/processor.h \
  /work/kernel/arch/arm64/include/asm/vdso/processor.h \
  /work/kernel/arch/arm64/include/asm/hw_breakpoint.h \
  /work/kernel/arch/arm64/include/asm/virt.h \
    $(wildcard include/config/KVM) \
  /work/kernel/arch/arm64/include/asm/sections.h \
  /work/kernel/include/asm-generic/sections.h \
  /work/kernel/arch/arm64/include/asm/kasan.h \
  /work/kernel/arch/arm64/include/asm/mte-kasan.h \
  /work/kernel/arch/arm64/include/asm/mte-def.h \
  /work/kernel/arch/arm64/include/asm/pgtable-types.h \
    $(wildcard include/config/PGTABLE_LEVELS) \
  /work/kernel/include/asm-generic/pgtable-nopud.h \
  /work/kernel/include/asm-generic/pgtable-nop4d.h \
  /work/kernel/arch/arm64/include/asm/pgtable-hwdef.h \
    $(wildcard include/config/ARM64_CONT_PTE_SHIFT) \
    $(wildcard include/config/ARM64_CONT_PMD_SHIFT) \
    $(wildcard include/config/ARM64_VA_BITS_52) \
  /work/kernel/arch/arm64/include/asm/pointer_auth.h \
  /work/kernel/include/uapi/linux/prctl.h \
  /work/kernel/include/linux/random.h \
    $(wildcard include/config/ARCH_RANDOM) \
  /work/kernel/include/linux/once.h \
  /work/kernel/include/uapi/linux/random.h \
  /work/kernel/include/uapi/linux/ioctl.h \
  arch/arm64/include/generated/uapi/asm/ioctl.h \
  /work/kernel/include/asm-generic/ioctl.h \
  /work/kernel/include/uapi/asm-generic/ioctl.h \
  /work/kernel/include/linux/irqnr.h \
  /work/kernel/include/uapi/linux/irqnr.h \
  /work/kernel/include/linux/prandom.h \
  /work/kernel/include/linux/percpu.h \
    $(wildcard include/config/NEED_PER_CPU_EMBED_FIRST_CHUNK) \
    $(wildcard include/config/NEED_PER_CPU_PAGE_FIRST_CHUNK) \
  /work/kernel/arch/arm64/include/asm/archrandom.h \
  /work/kernel/include/linux/arm-smccc.h \
    $(wildcard include/config/ARM64) \
    $(wildcard include/config/HAVE_ARM_SMCCC) \
    $(wildcard include/config/ARM) \
  /work/kernel/arch/arm64/include/asm/spectre.h \
  /work/kernel/arch/arm64/include/asm/fpsimd.h \
  /work/kernel/arch/arm64/include/uapi/asm/sigcontext.h \
  /work/kernel/include/linux/rcutree.h \
  /work/kernel/include/linux/wait.h \
  /work/kernel/include/linux/spinlock.h \
  arch/arm64/include/generated/asm/mmiowb.h \
  /work/kernel/include/asm-generic/mmiowb.h \
    $(wildcard include/config/MMIOWB) \
  /work/kernel/arch/arm64/include/asm/spinlock.h \
  arch/arm64/include/generated/asm/qspinlock.h \
  /work/kernel/include/asm-generic/qspinlock.h \
  arch/arm64/include/generated/asm/qrwlock.h \
  /work/kernel/include/asm-generic/qrwlock.h \
  /work/kernel/include/linux/rwlock.h \
    $(wildcard include/config/PREEMPT) \
  /work/kernel/include/linux/spinlock_api_smp.h \
    $(wildcard include/config/INLINE_SPIN_LOCK) \
    $(wildcard include/config/INLINE_SPIN_LOCK_BH) \
    $(wildcard include/config/INLINE_SPIN_LOCK_IRQ) \
    $(wildcard include/config/INLINE_SPIN_LOCK_IRQSAVE) \
    $(wildcard include/config/INLINE_SPIN_TRYLOCK) \
    $(wildcard include/config/INLINE_SPIN_TRYLOCK_BH) \
    $(wildcard include/config/UNINLINE_SPIN_UNLOCK) \
    $(wildcard include/config/INLINE_SPIN_UNLOCK_BH) \
    $(wildcard include/config/INLINE_SPIN_UNLOCK_IRQ) \
    $(wildcard include/config/INLINE_SPIN_UNLOCK_IRQRESTORE) \
    $(wildcard include/config/GENERIC_LOCKBREAK) \
  /work/kernel/include/linux/rwlock_api_smp.h \
    $(wildcard include/config/INLINE_READ_LOCK) \
    $(wildcard include/config/INLINE_WRITE_LOCK) \
    $(wildcard include/config/INLINE_READ_LOCK_BH) \
    $(wildcard include/config/INLINE_WRITE_LOCK_BH) \
    $(wildcard include/config/INLINE_READ_LOCK_IRQ) \
    $(wildcard include/config/INLINE_WRITE_LOCK_IRQ) \
    $(wildcard include/config/INLINE_READ_LOCK_IRQSAVE) \
    $(wildcard include/config/INLINE_WRITE_LOCK_IRQSAVE) \
    $(wildcard include/config/INLINE_READ_TRYLOCK) \
    $(wildcard include/config/INLINE_WRITE_TRYLOCK) \
    $(wildcard include/config/INLINE_READ_UNLOCK) \
    $(wildcard include/config/INLINE_WRITE_UNLOCK) \
    $(wildcard include/config/INLINE_READ_UNLOCK_BH) \
    $(wildcard include/config/INLINE_WRITE_UNLOCK_BH) \
    $(wildcard include/config/INLINE_READ_UNLOCK_IRQ) \
    $(wildcard include/config/INLINE_WRITE_UNLOCK_IRQ) \
    $(wildcard include/config/INLINE_READ_UNLOCK_IRQRESTORE) \
    $(wildcard include/config/INLINE_WRITE_UNLOCK_IRQRESTORE) \
  /work/kernel/include/uapi/linux/wait.h \
  /work/kernel/include/linux/refcount.h \
  /work/kernel/include/linux/sem.h \
  /work/kernel/include/uapi/linux/sem.h \
  /work/kernel/include/linux/ipc.h \
  /work/kernel/include/linux/uidgid.h \
    $(wildcard include/config/MULTIUSER) \
    $(wildcard include/config/USER_NS) \
  /work/kernel/include/linux/highuid.h \
  /work/kernel/include/linux/rhashtable-types.h \
  /work/kernel/include/linux/mutex.h \
    $(wildcard include/config/MUTEX_SPIN_ON_OWNER) \
  /work/kernel/include/linux/osq_lock.h \
  /work/kernel/include/linux/debug_locks.h \
  /work/kernel/include/linux/workqueue.h \
    $(wildcard include/config/DEBUG_OBJECTS_WORK) \
    $(wildcard include/config/FREEZER) \
    $(wildcard include/config/WQ_WATCHDOG) \
  /work/kernel/include/linux/timer.h \
    $(wildcard include/config/DEBUG_OBJECTS_TIMERS) \
    $(wildcard include/config/NO_HZ_COMMON) \
  /work/kernel/include/linux/ktime.h \
  /work/kernel/include/linux/jiffies.h \
  /work/kernel/include/vdso/jiffies.h \
  include/generated/timeconst.h \
  /work/kernel/include/vdso/ktime.h \
  /work/kernel/include/linux/timekeeping.h \
    $(wildcard include/config/GENERIC_CMOS_UPDATE) \
  /work/kernel/include/linux/clocksource_ids.h \
  /work/kernel/include/linux/debugobjects.h \
    $(wildcard include/config/DEBUG_OBJECTS) \
    $(wildcard include/config/DEBUG_OBJECTS_FREE) \
  /work/kernel/include/uapi/linux/ipc.h \
  arch/arm64/include/generated/uapi/asm/ipcbuf.h \
  /work/kernel/include/uapi/asm-generic/ipcbuf.h \
  arch/arm64/include/generated/uapi/asm/sembuf.h \
  /work/kernel/include/uapi/asm-generic/sembuf.h \
  /work/kernel/include/linux/shm.h \
  /work/kernel/arch/arm64/include/asm/page.h \
  /work/kernel/include/linux/personality.h \
  /work/kernel/include/uapi/linux/personality.h \
  /work/kernel/include/asm-generic/getorder.h \
  /work/kernel/include/uapi/linux/shm.h \
  /work/kernel/include/uapi/asm-generic/hugetlb_encode.h \
  arch/arm64/include/generated/uapi/asm/shmbuf.h \
  /work/kernel/include/uapi/asm-generic/shmbuf.h \
  /work/kernel/arch/arm64/include/asm/shmparam.h \
  /work/kernel/include/asm-generic/shmparam.h \
  /work/kernel/include/linux/plist.h \
    $(wildcard include/config/DEBUG_PLIST) \
  /work/kernel/include/linux/hrtimer.h \
    $(wildcard include/config/HIGH_RES_TIMERS) \
    $(wildcard include/config/TIME_LOW_RES) \
    $(wildcard include/config/TIMERFD) \
  /work/kernel/include/linux/hrtimer_defs.h \
  /work/kernel/include/linux/rbtree.h \
  /work/kernel/include/linux/rbtree_types.h \
  /work/kernel/include/linux/seqlock.h \
  /work/kernel/include/linux/ww_mutex.h \
    $(wildcard include/config/DEBUG_RT_MUTEXES) \
    $(wildcard include/config/DEBUG_WW_MUTEX_SLOWPATH) \
  /work/kernel/include/linux/rtmutex.h \
  /work/kernel/include/linux/timerqueue.h \
  /work/kernel/include/linux/seccomp.h \
    $(wildcard include/config/SECCOMP) \
    $(wildcard include/config/HAVE_ARCH_SECCOMP_FILTER) \
    $(wildcard include/config/SECCOMP_FILTER) \
    $(wildcard include/config/CHECKPOINT_RESTORE) \
    $(wildcard include/config/SECCOMP_CACHE_DEBUG) \
  /work/kernel/include/uapi/linux/seccomp.h \
  /work/kernel/arch/arm64/include/asm/seccomp.h \
  /work/kernel/arch/arm64/include/asm/unistd.h \
  /work/kernel/arch/arm64/include/uapi/asm/unistd.h \
  /work/kernel/include/uapi/asm-generic/unistd.h \
  /work/kernel/include/asm-generic/seccomp.h \
  /work/kernel/include/uapi/linux/unistd.h \
  /work/kernel/include/linux/nodemask.h \
    $(wildcard include/config/HIGHMEM) \
  /work/kernel/include/linux/numa.h \
    $(wildcard include/config/NODES_SHIFT) \
    $(wildcard include/config/NUMA_KEEP_MEMINFO) \
  /work/kernel/include/linux/resource.h \
  /work/kernel/include/uapi/linux/resource.h \
  arch/arm64/include/generated/uapi/asm/resource.h \
  /work/kernel/include/asm-generic/resource.h \
  /work/kernel/include/uapi/asm-generic/resource.h \
  /work/kernel/include/linux/latencytop.h \
  /work/kernel/include/linux/sched/prio.h \
  /work/kernel/include/linux/sched/types.h \
  /work/kernel/include/linux/signal_types.h \
    $(wildcard include/config/OLD_SIGACTION) \
  /work/kernel/include/uapi/linux/signal.h \
  /work/kernel/arch/arm64/include/asm/signal.h \
  /work/kernel/arch/arm64/include/uapi/asm/signal.h \
  /work/kernel/include/asm-generic/signal.h \
  /work/kernel/include/uapi/asm-generic/signal.h \
  /work/kernel/include/uapi/asm-generic/signal-defs.h \
  arch/arm64/include/generated/uapi/asm/siginfo.h \
  /work/kernel/include/uapi/asm-generic/siginfo.h \
  /work/kernel/include/linux/syscall_user_dispatch.h \
  /work/kernel/include/linux/mm_types_task.h \
    $(wildcard include/config/ARCH_WANT_BATCHED_UNMAP_TLB_FLUSH) \
    $(wildcard include/config/SPLIT_PTLOCK_CPUS) \
    $(wildcard include/config/ARCH_ENABLE_SPLIT_PMD_PTLOCK) \
  /work/kernel/include/linux/task_io_accounting.h \
    $(wildcard include/config/TASK_IO_ACCOUNTING) \
  /work/kernel/include/linux/posix-timers.h \
  /work/kernel/include/linux/alarmtimer.h \
    $(wildcard include/config/RTC_CLASS) \
  /work/kernel/include/linux/task_work.h \
  /work/kernel/include/uapi/linux/rseq.h \
  /work/kernel/include/linux/kcsan.h \
  arch/arm64/include/generated/asm/kmap_size.h \
  /work/kernel/include/asm-generic/kmap_size.h \
    $(wildcard include/config/DEBUG_KMAP_LOCAL) \
  /work/kernel/include/linux/sched/task_stack.h \
    $(wildcard include/config/STACK_GROWSUP) \
    $(wildcard include/config/DEBUG_STACK_USAGE) \
  /work/kernel/include/uapi/linux/magic.h \
  /work/kernel/include/uapi/linux/stat.h \
  /work/kernel/include/linux/buildid.h \
    $(wildcard include/config/CRASH_CORE) \
  /work/kernel/include/linux/mm_types.h \
    $(wildcard include/config/HAVE_ALIGNED_STRUCT_PAGE) \
    $(wildcard include/config/USERFAULTFD) \
    $(wildcard include/config/SPECULATIVE_PAGE_FAULT) \
    $(wildcard include/config/SWAP) \
    $(wildcard include/config/HAVE_ARCH_COMPAT_MMAP_BASES) \
    $(wildcard include/config/MEMBARRIER) \
    $(wildcard include/config/AIO) \
    $(wildcard include/config/MMU_NOTIFIER) \
    $(wildcard include/config/TRANSPARENT_HUGEPAGE) \
    $(wildcard include/config/HUGETLB_PAGE) \
    $(wildcard include/config/IOMMU_SUPPORT) \
  /work/kernel/include/linux/auxvec.h \
  /work/kernel/include/uapi/linux/auxvec.h \
  /work/kernel/arch/arm64/include/uapi/asm/auxvec.h \
  /work/kernel/include/linux/kref.h \
  /work/kernel/include/linux/rwsem.h \
    $(wildcard include/config/RWSEM_SPIN_ON_OWNER) \
    $(wildcard include/config/DEBUG_RWSEMS) \
  /work/kernel/include/linux/err.h \
  /work/kernel/include/linux/completion.h \
  /work/kernel/include/linux/swait.h \
  /work/kernel/include/linux/uprobes.h \
  /work/kernel/arch/arm64/include/asm/uprobes.h \
  /work/kernel/arch/arm64/include/asm/debug-monitors.h \
  /work/kernel/arch/arm64/include/asm/esr.h \
  /work/kernel/arch/arm64/include/asm/probes.h \
  /work/kernel/include/linux/page-flags-layout.h \
  include/generated/bounds.h \
  /work/kernel/arch/arm64/include/asm/sparsemem.h \
    $(wildcard include/config/ARM64_MEMMAP_ON_MEMORY) \
  /work/kernel/arch/arm64/include/asm/mmu.h \
  /work/kernel/include/linux/kmod.h \
  /work/kernel/include/linux/umh.h \
  /work/kernel/include/linux/gfp.h \
    $(wildcard include/config/CMA) \
    $(wildcard include/config/ZONE_DMA) \
    $(wildcard include/config/ZONE_DMA32) \
    $(wildcard include/config/ZONE_DEVICE) \
    $(wildcard include/config/PM_SLEEP) \
    $(wildcard include/config/CONTIG_ALLOC) \
  /work/kernel/include/linux/mmzone.h \
    $(wildcard include/config/FORCE_MAX_ZONEORDER) \
    $(wildcard include/config/MEMORY_ISOLATION) \
    $(wildcard include/config/LRU_GEN_STATS) \
    $(wildcard include/config/MEMORY_HOTPLUG) \
    $(wildcard include/config/PAGE_EXTENSION) \
    $(wildcard include/config/DEFERRED_STRUCT_PAGE_INIT) \
    $(wildcard include/config/HAVE_MEMORYLESS_NODES) \
    $(wildcard include/config/SPARSEMEM_EXTREME) \
    $(wildcard include/config/HAVE_ARCH_PFN_VALID) \
  /work/kernel/include/linux/pageblock-flags.h \
    $(wildcard include/config/HUGETLB_PAGE_SIZE_VARIABLE) \
  /work/kernel/include/linux/page-flags.h \
    $(wildcard include/config/ARCH_USES_PG_UNCACHED) \
    $(wildcard include/config/MEMORY_FAILURE) \
    $(wildcard include/config/PAGE_IDLE_FLAG) \
    $(wildcard include/config/THP_SWAP) \
    $(wildcard include/config/KSM) \
  /work/kernel/include/linux/local_lock.h \
  /work/kernel/include/linux/local_lock_internal.h \
  /work/kernel/include/linux/memory_hotplug.h \
    $(wildcard include/config/ARCH_HAS_ADD_PAGES) \
    $(wildcard include/config/HAVE_ARCH_NODEDATA_EXTENSION) \
    $(wildcard include/config/MEMORY_HOTREMOVE) \
  /work/kernel/include/linux/notifier.h \
  /work/kernel/include/linux/srcu.h \
    $(wildcard include/config/TINY_SRCU) \
    $(wildcard include/config/SRCU) \
  /work/kernel/include/linux/rcu_segcblist.h \
  /work/kernel/include/linux/srcutree.h \
  /work/kernel/include/linux/rcu_node_tree.h \
    $(wildcard include/config/RCU_FANOUT) \
    $(wildcard include/config/RCU_FANOUT_LEAF) \
  /work/kernel/include/linux/topology.h \
    $(wildcard include/config/USE_PERCPU_NUMA_NODE_ID) \
    $(wildcard include/config/SCHED_SMT) \
  /work/kernel/include/linux/arch_topology.h \
    $(wildcard include/config/GENERIC_ARCH_TOPOLOGY) \
  /work/kernel/arch/arm64/include/asm/topology.h \
  /work/kernel/include/asm-generic/topology.h \
  /work/kernel/include/linux/sysctl.h \
    $(wildcard include/config/SYSCTL) \
  /work/kernel/include/uapi/linux/sysctl.h \
  /work/kernel/include/linux/elf.h \
    $(wildcard include/config/ARCH_USE_GNU_PROPERTY) \
    $(wildcard include/config/ARCH_HAVE_ELF_PROT) \
  /work/kernel/arch/arm64/include/asm/elf.h \
    $(wildcard include/config/COMPAT_VDSO) \
  arch/arm64/include/generated/asm/user.h \
  /work/kernel/include/asm-generic/user.h \
  /work/kernel/include/uapi/linux/elf.h \
  /work/kernel/include/uapi/linux/elf-em.h \
  /work/kernel/include/linux/fs.h \
    $(wildcard include/config/READ_ONLY_THP_FOR_FS) \
    $(wildcard include/config/FS_POSIX_ACL) \
    $(wildcard include/config/CGROUP_WRITEBACK) \
    $(wildcard include/config/IMA) \
    $(wildcard include/config/FILE_LOCKING) \
    $(wildcard include/config/FSNOTIFY) \
    $(wildcard include/config/FS_ENCRYPTION) \
    $(wildcard include/config/FS_VERITY) \
    $(wildcard include/config/EPOLL) \
    $(wildcard include/config/UNICODE) \
    $(wildcard include/config/QUOTA) \
    $(wildcard include/config/FS_DAX) \
    $(wildcard include/config/MIGRATION) \
  /work/kernel/include/linux/wait_bit.h \
  /work/kernel/include/linux/kdev_t.h \
  /work/kernel/include/uapi/linux/kdev_t.h \
  /work/kernel/include/linux/dcache.h \
  /work/kernel/include/linux/rculist_bl.h \
  /work/kernel/include/linux/list_bl.h \
  /work/kernel/include/linux/bit_spinlock.h \
  /work/kernel/include/linux/lockref.h \
    $(wildcard include/config/ARCH_USE_CMPXCHG_LOCKREF) \
  /work/kernel/include/linux/stringhash.h \
    $(wildcard include/config/DCACHE_WORD_ACCESS) \
  /work/kernel/include/linux/hash.h \
    $(wildcard include/config/HAVE_ARCH_HASH) \
  /work/kernel/include/linux/path.h \
  /work/kernel/include/linux/list_lru.h \
    $(wildcard include/config/MEMCG_KMEM) \
  /work/kernel/include/linux/shrinker.h \
  /work/kernel/include/linux/radix-tree.h \
  /work/kernel/include/linux/xarray.h \
    $(wildcard include/config/XARRAY_MULTI) \
  /work/kernel/include/linux/capability.h \
  /work/kernel/include/uapi/linux/capability.h \
  /work/kernel/include/linux/semaphore.h \
  /work/kernel/include/linux/fcntl.h \
    $(wildcard include/config/ARCH_32BIT_OFF_T) \
  /work/kernel/include/uapi/linux/fcntl.h \
  /work/kernel/arch/arm64/include/uapi/asm/fcntl.h \
  /work/kernel/include/uapi/asm-generic/fcntl.h \
  /work/kernel/include/uapi/linux/openat2.h \
  /work/kernel/include/linux/migrate_mode.h \
  /work/kernel/include/linux/percpu-rwsem.h \
  /work/kernel/include/linux/rcuwait.h \
  /work/kernel/include/linux/sched/signal.h \
    $(wildcard include/config/SCHED_AUTOGROUP) \
    $(wildcard include/config/BSD_PROCESS_ACCT) \
    $(wildcard include/config/TASKSTATS) \
  /work/kernel/include/linux/signal.h \
    $(wildcard include/config/PROC_FS) \
  /work/kernel/include/linux/sched/jobctl.h \
  /work/kernel/include/linux/sched/task.h \
    $(wildcard include/config/HAVE_EXIT_THREAD) \
    $(wildcard include/config/ARCH_WANTS_DYNAMIC_TASK_STRUCT) \
    $(wildcard include/config/HAVE_ARCH_THREAD_STRUCT_WHITELIST) \
  /work/kernel/include/linux/uaccess.h \
    $(wildcard include/config/SET_FS) \
  /work/kernel/include/linux/fault-inject-usercopy.h \
    $(wildcard include/config/FAULT_INJECTION_USERCOPY) \
  /work/kernel/arch/arm64/include/asm/uaccess.h \
  /work/kernel/arch/arm64/include/asm/kernel-pgtable.h \
    $(wildcard include/config/RANDOMIZE_BASE) \
  /work/kernel/arch/arm64/include/asm/mte.h \
  /work/kernel/include/linux/bitfield.h \
  /work/kernel/arch/arm64/include/asm/extable.h \
    $(wildcard include/config/BPF_JIT) \
  /work/kernel/include/linux/cred.h \
    $(wildcard include/config/DEBUG_CREDENTIALS) \
  /work/kernel/include/linux/key.h \
    $(wildcard include/config/KEY_NOTIFICATIONS) \
    $(wildcard include/config/NET) \
  /work/kernel/include/linux/assoc_array.h \
    $(wildcard include/config/ASSOCIATIVE_ARRAY) \
  /work/kernel/include/linux/sched/user.h \
    $(wildcard include/config/WATCH_QUEUE) \
  /work/kernel/include/linux/percpu_counter.h \
  /work/kernel/include/linux/ratelimit.h \
  /work/kernel/include/linux/rcu_sync.h \
  /work/kernel/include/linux/delayed_call.h \
  /work/kernel/include/linux/uuid.h \
  /work/kernel/include/uapi/linux/uuid.h \
  /work/kernel/include/linux/errseq.h \
  /work/kernel/include/linux/ioprio.h \
  /work/kernel/include/linux/sched/rt.h \
  /work/kernel/include/linux/iocontext.h \
  /work/kernel/include/uapi/linux/ioprio.h \
  /work/kernel/include/linux/fs_types.h \
  /work/kernel/include/linux/mount.h \
  /work/kernel/include/uapi/linux/fs.h \
  /work/kernel/include/linux/quota.h \
    $(wildcard include/config/QUOTA_NETLINK_INTERFACE) \
  /work/kernel/include/uapi/linux/dqblk_xfs.h \
  /work/kernel/include/linux/dqblk_v1.h \
  /work/kernel/include/linux/dqblk_v2.h \
  /work/kernel/include/linux/dqblk_qtree.h \
  /work/kernel/include/linux/projid.h \
  /work/kernel/include/uapi/linux/quota.h \
  /work/kernel/include/linux/nfs_fs_i.h \
  /work/kernel/include/linux/kobject.h \
    $(wildcard include/config/UEVENT_HELPER) \
    $(wildcard include/config/DEBUG_KOBJECT_RELEASE) \
  /work/kernel/include/linux/sysfs.h \
  /work/kernel/include/linux/kernfs.h \
    $(wildcard include/config/KERNFS) \
  /work/kernel/include/linux/idr.h \
  /work/kernel/include/linux/kobject_ns.h \
  /work/kernel/include/linux/moduleparam.h \
    $(wildcard include/config/ALPHA) \
    $(wildcard include/config/IA64) \
    $(wildcard include/config/PPC64) \
  /work/kernel/include/linux/rbtree_latch.h \
  /work/kernel/include/linux/error-injection.h \
  /work/kernel/include/asm-generic/error-injection.h \
  /work/kernel/include/linux/tracepoint-defs.h \
  /work/kernel/include/linux/static_key.h \
  /work/kernel/include/linux/cfi.h \
    $(wildcard include/config/CFI_CLANG_SHADOW) \
  /work/kernel/arch/arm64/include/asm/module.h \
    $(wildcard include/config/ARM64_MODULE_PLTS) \
    $(wildcard include/config/DYNAMIC_FTRACE) \
    $(wildcard include/config/ARM64_ERRATUM_843419) \
  /work/kernel/include/asm-generic/module.h \
    $(wildcard include/config/HAVE_MOD_ARCH_SPECIFIC) \
    $(wildcard include/config/MODULES_USE_ELF_REL) \
    $(wildcard include/config/MODULES_USE_ELF_RELA) \
  /work/kernel/include/linux/kdebug.h \
  arch/arm64/include/generated/asm/kdebug.h \
  /work/kernel/include/asm-generic/kdebug.h \
  /work/kernel/include/linux/delay.h \
  arch/arm64/include/generated/asm/delay.h \
  /work/kernel/include/asm-generic/delay.h \
  /work/kernel/include/linux/kprobes.h \
    $(wildcard include/config/KPROBES_SANITY_TEST) \
    $(wildcard include/config/OPTPROBES) \
    $(wildcard include/config/KPROBES_ON_FTRACE) \
  /work/kernel/include/linux/ftrace.h \
    $(wildcard include/config/FUNCTION_TRACER) \
    $(wildcard include/config/HAVE_DYNAMIC_FTRACE_WITH_ARGS) \
    $(wildcard include/config/DYNAMIC_FTRACE_WITH_REGS) \
    $(wildcard include/config/DYNAMIC_FTRACE_WITH_DIRECT_CALLS) \
    $(wildcard include/config/HAVE_DYNAMIC_FTRACE_WITH_DIRECT_CALLS) \
    $(wildcard include/config/STACK_TRACER) \
    $(wildcard include/config/FRAME_POINTER) \
    $(wildcard include/config/FUNCTION_PROFILER) \
    $(wildcard include/config/FTRACE_SYSCALLS) \
  /work/kernel/include/linux/trace_recursion.h \
    $(wildcard include/config/FTRACE_RECORD_RECURSION) \
  /work/kernel/include/linux/interrupt.h \
    $(wildcard include/config/IRQ_FORCED_THREADING) \
    $(wildcard include/config/GENERIC_IRQ_PROBE) \
    $(wildcard include/config/IRQ_TIMINGS) \
  /work/kernel/include/linux/irqreturn.h \
  /work/kernel/include/linux/hardirq.h \
  /work/kernel/include/linux/context_tracking_state.h \
    $(wildcard include/config/CONTEXT_TRACKING) \
  /work/kernel/include/linux/ftrace_irq.h \
    $(wildcard include/config/HWLAT_TRACER) \
    $(wildcard include/config/OSNOISE_TRACER) \
  /work/kernel/include/linux/vtime.h \
    $(wildcard include/config/VIRT_CPU_ACCOUNTING) \
    $(wildcard include/config/IRQ_TIME_ACCOUNTING) \
  /work/kernel/arch/arm64/include/asm/hardirq.h \
  /work/kernel/arch/arm64/include/asm/irq.h \
  /work/kernel/include/asm-generic/irq.h \
  /work/kernel/arch/arm64/include/asm/kvm_arm.h \
  /work/kernel/include/asm-generic/hardirq.h \
  /work/kernel/include/linux/irq.h \
    $(wildcard include/config/GENERIC_IRQ_EFFECTIVE_AFF_MASK) \
    $(wildcard include/config/GENERIC_IRQ_IPI) \
    $(wildcard include/config/IRQ_DOMAIN_HIERARCHY) \
    $(wildcard include/config/GENERIC_IRQ_MIGRATION) \
    $(wildcard include/config/GENERIC_PENDING_IRQ) \
    $(wildcard include/config/HARDIRQS_SW_RESEND) \
    $(wildcard include/config/GENERIC_IRQ_LEGACY) \
    $(wildcard include/config/GENERIC_IRQ_MULTI_HANDLER) \
  /work/kernel/include/linux/irqhandler.h \
  /work/kernel/include/linux/io.h \
    $(wildcard include/config/HAS_IOPORT_MAP) \
    $(wildcard include/config/PCI) \
  /work/kernel/arch/arm64/include/asm/io.h \
  /work/kernel/include/linux/pgtable.h \
    $(wildcard include/config/HIGHPTE) \
    $(wildcard include/config/ARCH_HAS_NONLEAF_PMD_YOUNG) \
    $(wildcard include/config/GUP_GET_PTE_LOW_HIGH) \
    $(wildcard include/config/HAVE_ARCH_TRANSPARENT_HUGEPAGE_PUD) \
    $(wildcard include/config/HAVE_ARCH_SOFT_DIRTY) \
    $(wildcard include/config/ARCH_ENABLE_THP_MIGRATION) \
    $(wildcard include/config/ARCH_HAS_PTE_DEVMAP) \
    $(wildcard include/config/HAVE_ARCH_HUGE_VMAP) \
    $(wildcard include/config/X86_ESPFIX64) \
  /work/kernel/arch/arm64/include/asm/pgtable.h \
  /work/kernel/arch/arm64/include/asm/proc-fns.h \
  /work/kernel/arch/arm64/include/asm/pgtable-prot.h \
  /work/kernel/arch/arm64/include/asm/tlbflush.h \
    $(wildcard include/config/ARM64_WORKAROUND_REPEAT_TLBI) \
  /work/kernel/arch/arm64/include/asm/fixmap.h \
    $(wildcard include/config/ACPI_APEI_GHES) \
    $(wildcard include/config/ARM_SDE_INTERFACE) \
    $(wildcard include/config/UNMAP_KERNEL_AT_EL0) \
  /work/kernel/arch/arm64/include/asm/boot.h \
  /work/kernel/include/asm-generic/fixmap.h \
  /work/kernel/include/asm-generic/pgtable_uffd.h \
    $(wildcard include/config/HAVE_ARCH_USERFAULTFD_WP) \
  arch/arm64/include/generated/asm/early_ioremap.h \
  /work/kernel/include/asm-generic/early_ioremap.h \
    $(wildcard include/config/GENERIC_EARLY_IOREMAP) \
  /work/kernel/include/asm-generic/io.h \
    $(wildcard include/config/GENERIC_IOMAP) \
    $(wildcard include/config/TRACE_MMIO_ACCESS) \
    $(wildcard include/config/GENERIC_IOREMAP) \
    $(wildcard include/config/VIRT_TO_BUS) \
    $(wildcard include/config/GENERIC_DEVMEM_IS_ALLOWED) \
  /work/kernel/include/asm-generic/pci_iomap.h \
    $(wildcard include/config/NO_GENERIC_PCI_IOPORT_MAP) \
    $(wildcard include/config/GENERIC_PCI_IOMAP) \
  /work/kernel/include/linux/logic_pio.h \
    $(wildcard include/config/INDIRECT_PIO) \
  /work/kernel/include/linux/fwnode.h \
  /work/kernel/include/linux/vmalloc.h \
    $(wildcard include/config/KASAN_VMALLOC) \
    $(wildcard include/config/HAVE_ARCH_HUGE_VMALLOC) \
  /work/kernel/include/linux/overflow.h \
  /work/kernel/arch/arm64/include/asm/vmalloc.h \
    $(wildcard include/config/PTDUMP_DEBUGFS) \
  /work/kernel/include/linux/slab.h \
    $(wildcard include/config/DEBUG_SLAB) \
    $(wildcard include/config/FAILSLAB) \
    $(wildcard include/config/HAVE_HARDENED_USERCOPY_ALLOCATOR) \
    $(wildcard include/config/SLAB) \
    $(wildcard include/config/SLUB) \
    $(wildcard include/config/SLOB) \
    $(wildcard include/config/CC_IS_GCC) \
    $(wildcard include/config/CLANG_VERSION) \
  /work/kernel/include/linux/percpu-refcount.h \
  /work/kernel/include/linux/kasan.h \
    $(wildcard include/config/KASAN_STACK) \
    $(wildcard include/config/KASAN_INLINE) \
  arch/arm64/include/generated/asm/irq_regs.h \
  /work/kernel/include/asm-generic/irq_regs.h \
  /work/kernel/include/linux/irqdesc.h \
    $(wildcard include/config/GENERIC_IRQ_DEBUGFS) \
    $(wildcard include/config/SPARSE_IRQ) \
    $(wildcard include/config/IRQ_DOMAIN) \
    $(wildcard include/config/HANDLE_DOMAIN_IRQ) \
  arch/arm64/include/generated/asm/hw_irq.h \
  /work/kernel/include/asm-generic/hw_irq.h \
  /work/kernel/include/linux/trace_clock.h \
  arch/arm64/include/generated/asm/trace_clock.h \
  /work/kernel/include/asm-generic/trace_clock.h \
  /work/kernel/include/linux/kallsyms.h \
    $(wildcard include/config/KALLSYMS_ALL) \
  /work/kernel/include/linux/mm.h \
    $(wildcard include/config/HAVE_ARCH_MMAP_RND_BITS) \
    $(wildcard include/config/HAVE_ARCH_MMAP_RND_COMPAT_BITS) \
    $(wildcard include/config/DEBUG_INFO_BTF) \
    $(wildcard include/config/MEM_SOFT_DIRTY) \
    $(wildcard include/config/ARCH_USES_HIGH_VMA_FLAGS) \
    $(wildcard include/config/ARCH_HAS_PKEYS) \
    $(wildcard include/config/PPC) \
    $(wildcard include/config/X86) \
    $(wildcard include/config/PARISC) \
    $(wildcard include/config/SPARC64) \
    $(wildcard include/config/HAVE_ARCH_USERFAULTFD_MINOR) \
    $(wildcard include/config/SHMEM) \
    $(wildcard include/config/DEV_PAGEMAP_OPS) \
    $(wildcard include/config/DEVICE_PRIVATE) \
    $(wildcard include/config/PCI_P2PDMA) \
    $(wildcard include/config/ARCH_HAS_PTE_SPECIAL) \
    $(wildcard include/config/DEBUG_VM_RB) \
    $(wildcard include/config/PAGE_POISONING) \
    $(wildcard include/config/INIT_ON_ALLOC_DEFAULT_ON) \
    $(wildcard include/config/INIT_ON_FREE_DEFAULT_ON) \
    $(wildcard include/config/DEBUG_PAGEALLOC) \
    $(wildcard include/config/HUGETLBFS) \
    $(wildcard include/config/MAPPING_DIRTY_HELPERS) \
    $(wildcard include/config/ANON_VMA_NAME) \
  /work/kernel/include/linux/mmap_lock.h \
    $(wildcard include/config/SPECULATIVE_PAGE_FAULT_STATS) \
  /work/kernel/include/linux/vm_event.h \
    $(wildcard include/config/VM_EVENT_COUNTERS) \
    $(wildcard include/config/DEBUG_TLBFLUSH) \
    $(wildcard include/config/DEBUG_VM_VMACACHE) \
  /work/kernel/include/linux/vm_event_item.h \
    $(wildcard include/config/MEMORY_BALLOON) \
    $(wildcard include/config/BALLOON_COMPACTION) \
  /work/kernel/include/linux/range.h \
  /work/kernel/include/linux/page_ext.h \
  /work/kernel/include/linux/stacktrace.h \
    $(wildcard include/config/STACKTRACE) \
    $(wildcard include/config/ARCH_STACKWALK) \
    $(wildcard include/config/HAVE_RELIABLE_STACKTRACE) \
  /work/kernel/include/linux/stackdepot.h \
    $(wildcard include/config/STACKDEPOT) \
  /work/kernel/include/linux/page_ref.h \
    $(wildcard include/config/DEBUG_PAGE_REF) \
  /work/kernel/include/linux/memremap.h \
  /work/kernel/include/linux/ioport.h \
  /work/kernel/include/linux/huge_mm.h \
  /work/kernel/include/linux/sched/coredump.h \
    $(wildcard include/config/CORE_DUMP_DEFAULT_ELF_HEADERS) \
  /work/kernel/include/linux/vmstat.h \
  /work/kernel/include/linux/ptrace.h \
  /work/kernel/include/linux/pid_namespace.h \
    $(wildcard include/config/PID_NS) \
  /work/kernel/include/linux/nsproxy.h \
  /work/kernel/include/linux/ns_common.h \
  /work/kernel/include/uapi/linux/ptrace.h \
  /work/kernel/arch/arm64/include/asm/ftrace.h \
  /work/kernel/include/linux/compat.h \
    $(wildcard include/config/ARCH_HAS_SYSCALL_WRAPPER) \
    $(wildcard include/config/X86_X32_ABI) \
    $(wildcard include/config/COMPAT_OLD_SIGACTION) \
    $(wildcard include/config/ODD_RT_SIGACTION) \
  /work/kernel/include/linux/socket.h \
  arch/arm64/include/generated/uapi/asm/socket.h \
  /work/kernel/include/uapi/asm-generic/socket.h \
  arch/arm64/include/generated/uapi/asm/sockios.h \
  /work/kernel/include/uapi/asm-generic/sockios.h \
  /work/kernel/include/uapi/linux/sockios.h \
  /work/kernel/include/linux/uio.h \
    $(wildcard include/config/ARCH_HAS_COPY_MC) \
  /work/kernel/include/uapi/linux/uio.h \
  /work/kernel/include/uapi/linux/socket.h \
  /work/kernel/include/uapi/linux/if.h \
  /work/kernel/include/uapi/linux/libc-compat.h \
  /work/kernel/include/uapi/linux/hdlc/ioctl.h \
  /work/kernel/include/uapi/linux/aio_abi.h \
  /work/kernel/arch/arm64/include/asm/syscall_wrapper.h \
  /work/kernel/include/linux/freelist.h \
  /work/kernel/arch/arm64/include/asm/kprobes.h \
  /work/kernel/include/asm-generic/kprobes.h \

/Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.o: $(deps_/Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.o)

$(deps_/Users/lin/Desktop/xiaomi13/native/airdrop/driver/die-logger/marionette_die_logger.o):
