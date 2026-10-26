"""Exercise radio.sh route ownership with a mocked ip command."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "app/src/main/assets/airdrop/radio.sh"
SOURCE = SCRIPT.read_text()
SETUP = SOURCE[SOURCE.index("setup_ipv6_route() {"):SOURCE.index("\nfail() {")]
CLEANUP = SOURCE[SOURCE.index("cleanup() {"):SOURCE.index('\nmkdir -p "$STATE"')]
INITIAL = """HOST_IF=awdl0
ROUTE_TABLE=7400
ROUTE_PREF=10990
ROUTE_OWNED=0
RULE_OWNED=0
SOURCE_RULE_OWNED=0
HOST_IPV6=
CHILD=
HOLD_WAS=
MUTE_WAS=
MGMT_WAS=
TAP_OWNED=0
mon_close() { :; }
"""
MOCK = """#!/bin/sh
echo "$*" >> "$LOG"
case "$*" in
  "-6 route show table 7400") [ "$CASE" != table ] || echo occupied ;;
  "-6 rule show") [ "$CASE" != rule ] || echo "10990: existing rule" ;;
  "-6 -o addr show dev awdl0 scope link") echo "107: awdl0 inet6 fe80::1234/64 scope link" ;;
  "-6 rule add pref 10990"*) [ "$CASE" != fail ] || exit 1 ;;
esac
exit 0
"""

class HotspotTest(unittest.TestCase):
    def test_actual_interfaces_not_client_started_state(self):
        source = SCRIPT.read_text()
        begin = source.index("hotspot_active() {")
        function = source[begin:source.index("\nif hotspot_active;", begin)]
        for interfaces, active in [
            ("Interface wlan0\n type managed\nInterface p2p0\n type P2P-device", False),
            ("Interface wlan0\n type managed\nInterface awdlmon\n type monitor", False),
            ("Interface wlan1\n type AP", True),
            ("Interface wlan1\n type AP/VLAN", True),
        ]:
            with self.subTest(interfaces=interfaces):
                env = dict(os.environ, IW_OUTPUT=interfaces)
                result = subprocess.run(["sh", "-c",
                    'iw() { printf "%s\\n" "$IW_OUTPUT"; }\n' + function + "\nhotspot_active"],
                    env=env, capture_output=True)
                self.assertEqual(result.returncode == 0, active)

class RoutesTest(unittest.TestCase):
    def exercise(self, case=""):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ip = root / "ip"
            ip.write_text(MOCK)
            ip.chmod(0o755)
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"],
                       LOG=str(root / "commands"), CASE=case, STATE=directory)
            result = subprocess.run(["sh", "-c", INITIAL + SETUP + CLEANUP +
                                     '\nsetup_ipv6_route; rc=$?; cleanup; exit "$rc"'],
                                    env=env, text=True, capture_output=True, timeout=5)
            return result.returncode, (root / "commands").read_text()

    def test_owns_and_removes_exact_routes(self):
        code, log = self.exercise()
        self.assertEqual(code, 0)
        self.assertIn("rule add pref 10990 to fe80::/64 oif awdl0 lookup 7400", log)
        self.assertIn("rule add pref 10991 from fe80::1234/128 to fe80::/64 lookup 7400", log)
        self.assertIn("rule del pref 10991 from fe80::1234/128 to fe80::/64 lookup 7400", log)
        self.assertIn("route del fe80::/64 dev awdl0 table 7400", log)
        self.assertNotIn("flush", log)

    def test_existing_resources_are_not_modified(self):
        for case in ["table", "rule"]:
            with self.subTest(case=case):
                code, log = self.exercise(case)
                self.assertNotEqual(code, 0)
                self.assertNotIn(" add ", log)
                self.assertNotIn(" del ", log)

    def test_partial_failure_removes_only_owned_route(self):
        code, log = self.exercise("fail")
        self.assertNotEqual(code, 0)
        self.assertIn("route del fe80::/64 dev awdl0 table 7400", log)
        self.assertNotIn("rule del", log)

if __name__ == "__main__":
    unittest.main()
