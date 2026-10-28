"""Offline shell fault injection for actual stack-loader lifecycle functions."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'native/airdrop/tools/awdl-stack-load.sh'
SOURCE = SCRIPT.read_text()
FUNCTIONS = SOURCE[SOURCE.index('wait_link() {'):SOURCE.index('\ncase "${1:-status}" in')]
MOCK = r'''
sleep() { :; }
settings() { echo "${SCAN_SETTING:-1}"; }
pidof() { cat "$STATE/pid"; }
getprop() { echo running; }
setprop() {
 echo "setprop $*" >> "$LOG"
 [ "$CASE" = restart_stuck ] || echo 200 > "$STATE/pid"
}
iw() { [ "$CASE" != no_link ] && echo 'Connected to 01:02:03:04:05:06'; }
run_bounded() {
 shift
 echo "$*" >> "$LOG"
 case "$*" in
  'dumpsys wifi')
   queries=$(cat "$STATE/queries" 2>/dev/null || echo 0)
   queries=$((queries + 1)); echo "$queries" > "$STATE/queries"
   if [ "$CASE" = late_recovery ] && [ "$queries" = 3 ]; then
    echo 'NumActiveModeManagers: 1'
   elif [ "$CASE" = scan_active ]; then echo 'NumActiveModeManagers: 1'; else echo 'NumActiveModeManagers: 0'; fi ;;
  'cmd wifi status')
   if [ "$CASE" = disable_stuck ]; then echo 'Wifi is enabled'; else echo 'Wifi is disabled'; fi ;;
  'svc wifi enable') [ "$(cat "$STATE/pid")" = 200 ] || exit 70 ;;
  rmmod*)
   [ "$CASE" != unload_fails ] || return 1
   rmdir "$STATE/sysmodule/$2" ;;
  insmod*)
   [ -z "$(ls "$STATE/sysmodule")" ] || [ "$2" = stock-wlan ] || exit 71
   case "$2" in
    stock-cfg) mkdir "$STATE/sysmodule/cfg80211" ;;
    stock-wlan) mkdir "$STATE/sysmodule/kiwi_v2" ;;
   esac ;;
 esac
}
'''

class StackLifecycleTest(unittest.TestCase):
    def exercise(self, command, case=''):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            (p/'pid').write_text('100\n')
            (p/'sysmodule').mkdir()
            for module in ['kiwi_v2','cfg80211']:(p/'sysmodule'/module).mkdir()
            env=dict(os.environ,STATE=directory,LOG=str(p/'commands'),CASE=case)
            code='SCAN_RESTORE=\nLOCK="$STATE/lock"\nSTOCK=stock-wlan\nSTOCK_CFG=stock-cfg\n'
            code+=FUNCTIONS.replace('/sys/module',str(p/'sysmodule'))+MOCK+'\n'+command
            result=subprocess.run(['sh','-c',code],env=env,capture_output=True,text=True,timeout=5)
            log=(p/'commands').read_text() if (p/'commands').exists() else ''
            return result,log

    def test_enable_waits_for_new_wificond(self):
        result,log=self.exercise('enable_after_reload')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertLess(log.index('ctl.restart wificond'),log.index('svc wifi enable'))

    def test_delayed_recovery_must_settle_before_wifi_enable(self):
        result,log=self.exercise('enable_after_reload','late_recovery')
        self.assertEqual(result.returncode,0,result.stderr)
        before_enable=log.split('svc wifi enable')[0]
        self.assertGreaterEqual(before_enable.count('dumpsys wifi'),15)
        self.assertIn('svc wifi disable',before_enable)
        self.assertEqual(log.count('setprop ctl.restart wificond'),1)

    def test_failed_wificond_restart_does_not_enable_wifi(self):
        result,log=self.exercise('enable_after_reload','restart_stuck')
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('svc wifi enable',log)

    def test_disable_failure_never_removes_modules(self):
        result,log=self.exercise('unload','disable_stuck')
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('rmmod',log)

    def test_scan_only_manager_blocks_unload_even_with_wifi_disabled(self):
        result,log=self.exercise('unload','scan_active')
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('rmmod',log)

    def test_scan_setting_restored_after_success_or_failure(self):
        for case in ['', 'unload_fails']:
            result,log=self.exercise('acquire_stack_lock || exit $?; restore_stock',case)
            self.assertIn('cmd wifi set-scan-always-available disabled',log)
            self.assertIn('cmd wifi set-scan-always-available enabled',log)
            self.assertLess(log.index('set-scan-always-available disabled'),log.index('svc wifi disable'))
            self.assertTrue(log.rstrip().endswith('cmd wifi set-scan-always-available enabled'))

    def test_unload_failure_never_loads_stock_over_existing_driver(self):
        result,log=self.exercise('restore_stock','unload_fails')
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('insmod',log)
        self.assertNotIn('svc wifi enable',log)

    def test_restore_order_and_association_verified(self):
        result,log=self.exercise('restore_stock')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('STOCK_LOADED_LINKED',result.stdout)
        operations=['svc wifi disable','rmmod kiwi_v2','rmmod cfg80211',
                    'insmod stock-cfg','insmod stock-wlan','setprop ctl.restart wificond','svc wifi enable']
        positions=[log.index(x) for x in operations]
        self.assertEqual(positions,sorted(positions))

    def test_no_link_never_claims_restore_success(self):
        result,log=self.exercise('restore_stock','no_link')
        self.assertNotEqual(result.returncode,0)
        self.assertIn('STOCK_LOADED_NO_LINK',result.stdout)
        self.assertNotIn('STOCK_LOADED_LINKED',result.stdout)
        self.assertEqual(log.count('setprop ctl.restart wificond'),1)

    def test_lock_blocks_second_operation(self):
        result,log=self.exercise('acquire_stack_lock || exit $?; acquire_stack_lock')
        self.assertEqual(result.returncode,9)
        self.assertEqual(log,'')

if __name__=='__main__':unittest.main()
