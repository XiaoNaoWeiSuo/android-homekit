// SPDX-License-Identifier: GPL-2.0-only
/* RAM-only diagnostics: observe kernel exceptions without consuming them.
 * No panic trigger, driver changes, persistent hooks or flash writes.
 */
#include <linux/module.h>
#include <linux/kdebug.h>
#include <linux/notifier.h>
#include <linux/delay.h>
#include <linux/atomic.h>
#include <linux/printk.h>
#include <linux/kprobes.h>
#include <asm/ptrace.h>

static unsigned int delay_ms;
module_param(delay_ms, uint, 0444);
MODULE_PARM_DESC(delay_ms, "Diagnostic exception delay, 0..500 ms; default 0");
static bool selftest;
module_param(selftest, bool, 0444);
MODULE_PARM_DESC(selftest, "Emit a diagnostic snapshot on load without causing a fault");
static atomic_t reporting = ATOMIC_INIT(0);
static bool observe_panic;
module_param(observe_panic, bool, 0444);
MODULE_PARM_DESC(observe_panic, "Request a supported kprobe at panic entry; default off");
static bool panic_probe_registered;
module_param(panic_probe_registered, bool, 0444);

static void report_snapshot(const char *label, struct die_args *args)
{
    pr_emerg("MARIONETTE_DIE_SNAPSHOT label=%s delay_ms=%u\n", label, delay_ms);
    if (args && args->regs) {
        pr_emerg("MARIONETTE_DIE_PC pc=%pS lr=%pS error=%lx\n",
                 (void *)args->regs->pc, (void *)args->regs->regs[30], args->err);
    }
    dump_stack();
    /* Before panic stops other CPUs, allow the unbuffered ADB log reader a
     * bounded opportunity to drain these records. The exception still follows
     * its normal path after this observer returns NOTIFY_DONE. */
    if (delay_ms)
        mdelay(delay_ms);
    pr_emerg("MARIONETTE_DIE_SNAPSHOT_END\n");
}

static int die_observer(struct notifier_block *block, unsigned long event, void *data)
{
    if (event != DIE_OOPS || atomic_cmpxchg(&reporting, 0, 1))
        return NOTIFY_DONE;
    report_snapshot("kernel_exception", data);
    atomic_set(&reporting, 0);
    return NOTIFY_DONE;
}

static struct notifier_block observer = {
    .notifier_call = die_observer,
    .priority = 200,
};

static int panic_entry_observer(struct kprobe *probe, struct pt_regs *regs)
{
    if (atomic_cmpxchg(&reporting, 0, 1))
        return 0;
    /* The normal panic notifier runs after other CPUs stop. Observe entry
     * before that point so the userspace reader can drain the snapshot. */
    report_snapshot("panic_entry", NULL);
    atomic_set(&reporting, 0);
    return 0;
}
NOKPROBE_SYMBOL(panic_entry_observer);

static struct kprobe panic_probe = {
    .symbol_name = "panic",
    .pre_handler = panic_entry_observer,
};

static int __init marionette_die_logger_init(void)
{
    int result;
    if (delay_ms > 500)
        return -EINVAL;
    result = register_die_notifier(&observer);
    if (result)
        return result;
    pr_info("MARIONETTE_DIE_LOGGER_REGISTERED delay_ms=%u\n", delay_ms);
    if (observe_panic) {
        result = register_kprobe(&panic_probe);
        if (result) {
            /* Honor the kernel's blacklist/support decision. Never modify
             * exception code or bypass a rejected instrumentation target. */
            pr_warn("MARIONETTE_PANIC_PROBE_UNAVAILABLE result=%d\n", result);
        } else {
            panic_probe_registered = true;
            pr_info("MARIONETTE_PANIC_PROBE_REGISTERED\n");
        }
    }
    if (selftest)
        report_snapshot("selftest_no_fault", NULL);
    return 0;
}

static void __exit marionette_die_logger_exit(void)
{
    if (panic_probe_registered)
        unregister_kprobe(&panic_probe);
    unregister_die_notifier(&observer);
    pr_info("MARIONETTE_DIE_LOGGER_UNREGISTERED\n");
}

module_init(marionette_die_logger_init);
module_exit(marionette_die_logger_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Bounded, volatile kernel exception diagnostics for Marionette WLAN research");
