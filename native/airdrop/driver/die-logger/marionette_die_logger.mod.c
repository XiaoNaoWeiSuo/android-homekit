#include <linux/module.h>
#define INCLUDE_VERMAGIC
#include <linux/build-salt.h>
#include <linux/elfnote-lto.h>
#include <linux/vermagic.h>
#include <linux/compiler.h>

BUILD_SALT;
BUILD_LTO_INFO;

MODULE_INFO(vermagic, VERMAGIC_STRING);
MODULE_INFO(name, KBUILD_MODNAME);

__visible struct module __this_module
__section(".gnu.linkonce.this_module") = {
	.name = KBUILD_MODNAME,
	.init = init_module,
#ifdef CONFIG_MODULE_UNLOAD
	.exit = cleanup_module,
#endif
	.arch = MODULE_ARCH_INIT,
};

#ifdef CONFIG_RETPOLINE
MODULE_INFO(retpoline, "Y");
#endif

static const struct modversion_info ____versions[]
__used __section("__versions") = {
	{ 0x222dd63, "module_layout" },
	{ 0xd14fef22, "cpu_hwcap_keys" },
	{ 0x14b89635, "arm64_const_caps_ready" },
	{ 0x8070288, "param_ops_bool" },
	{ 0x7c75e4a8, "param_ops_uint" },
	{ 0xdf7a4c69, "__ubsan_handle_cfi_check_fail_abort" },
	{ 0x121d958a, "unregister_die_notifier" },
	{ 0xeb78b1ed, "unregister_kprobe" },
	{ 0x472cf3b, "register_kprobe" },
	{ 0xb6261484, "register_die_notifier" },
	{ 0xeae3dfd6, "__const_udelay" },
	{ 0x6b2dc060, "dump_stack" },
	{ 0x92997ed8, "_printk" },
};

MODULE_INFO(depends, "");


MODULE_INFO(scmversion, "gc922fbc8b773-dirty");
