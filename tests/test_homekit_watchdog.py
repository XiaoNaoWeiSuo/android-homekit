"""Run the shipped shell policy with simulated Android platform commands."""
import os
import pathlib
import re
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class WatchdogPolicyTest(unittest.TestCase):
    def check_policy(self, enabled, foreground, heartbeat=195000):
        with tempfile.TemporaryDirectory(prefix="homekit-watchdog-") as directory:
            directory = pathlib.Path(directory)
            binary = directory / "bin"
            binary.mkdir()
            prefs = directory / "prefs.xml"
            prefs.write_text(f'<map><boolean name="driver_service_enabled" value="{str(enabled).lower()}" />'
                             f'<long name="runtime_heartbeat_elapsed" value="{heartbeat}" /></map>')
            calls = directory / "calls"
            commands = {
                "getprop": 'case "$1" in sys.boot_completed) echo 1;; *) echo true;; esac',
                "timeout": 'shift 3; exec "$@"',
                "dumpsys": f'echo "isForeground={str(foreground).lower()}"',
                "am": 'echo start >> "$WATCHDOG_CALLS"; echo "Starting service"',
                "log": ':',
                "sleep": ':',
                "pidof": 'echo 99999999',
            }
            for name, content in commands.items():
                path = binary / name
                path.write_text("#!/bin/sh\n" + content + "\n")
                path.chmod(0o755)
            source = (ROOT / "kernelsu-module/homekit-watchdog.sh").read_text()
            source = re.sub(r'^PREFS=.*$', f'PREFS="{prefs}"', source, flags=re.M)
            source = source.replace("/system/bin/", str(binary) + "/")
            source = re.sub(r'uptime_seconds\(\) \{[^\n]*\}', 'uptime_seconds() { echo 200; }', source)
            source = re.sub(r'pause_interval\(\) \{.*?\n\}', 'pause_interval() { exit 0; }', source, flags=re.S)
            script = directory / "homekit-watchdog.sh"
            script.write_text(source)
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"], WATCHDOG_CALLS=str(calls))
            subprocess.run(["/bin/sh", str(script)], env=env, check=True, timeout=5, capture_output=True)
            return calls.read_text().splitlines() if calls.exists() else []

    def test_disabled_driver_is_never_resurrected(self):
        self.assertEqual([], self.check_policy(False, False))

    def test_missing_enabled_service_is_recovered(self):
        self.assertEqual(["start"], self.check_policy(True, False))

    def test_healthy_foreground_service_is_left_running(self):
        self.assertEqual([], self.check_policy(True, True))

    def test_one_stale_heartbeat_after_sleep_does_not_restart_healthy_process(self):
        self.assertEqual([], self.check_policy(True, True, heartbeat=1))


if __name__ == "__main__":
    unittest.main()
