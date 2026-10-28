"""Real loopback validation of probe startup; never touches Wi-Fi or ADB."""
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]


class IpProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shutil.which("clang")
        if not compiler:
            raise unittest.SkipTest("clang unavailable")
        cls.build = tempfile.TemporaryDirectory()
        cls.binary = str(Path(cls.build.name) / "probe")
        subprocess.run([
            compiler, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            str(ROOT / "native/airdrop/tools/awdl-ip-probe.c"), "-o", cls.binary,
        ], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def start(self, folder, origin, port, peer_port, duration=2):
        log = folder / f"probe-{origin}.log"
        gate = folder / f"gate-{origin}"
        env = dict(os.environ, AWDL_IP_PROBE_START_FILE=str(gate))
        with log.open("wb") as out:
            proc = subprocess.Popen([
                self.binary, "lo0" if sys.platform == "darwin" else "lo",
                "::1", "::1", str(port), str(duration), str(origin), str(peer_port),
            ], env=env, stdout=out, stderr=subprocess.STDOUT)
        self.addCleanup(self.stop, proc)
        until = time.monotonic() + 3
        while "ready " not in log.read_text() and time.monotonic() < until:
            if proc.poll() is not None:
                self.fail(log.read_text())
            time.sleep(.02)
        self.assertIn("ready ", log.read_text())
        return proc, gate, log

    @staticmethod
    def stop(proc):
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=3)

    @staticmethod
    def ports():
        # Hold both ephemeral reservations at once to ensure distinct ports.
        with socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as a, \
                socket.socket(socket.AF_INET6, socket.SOCK_DGRAM) as b:
            a.bind(("::1", 0))
            b.bind(("::1", 0))
            return a.getsockname()[1], b.getsockname()[1]

    def test_both_bound_before_start_then_real_echo(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            a, b = self.ports()
            first = self.start(folder, 1, a, b)
            # Deliberate startup skew, like two independent ADB commands.
            time.sleep(.15)
            second = self.start(folder, 2, b, a)
            for proc, _, log in (first, second):
                self.assertIsNone(proc.poll())
                self.assertNotIn("started ", log.read_text())
            first[1].touch()
            second[1].touch()
            for proc, _, log in (first, second):
                self.assertEqual(proc.wait(timeout=5), 0, log.read_text())
                text = log.read_text()
                result = dict(re.findall(r"(\w+)=(\d+)", text.split("result ")[-1]))
                self.assertGreaterEqual(int(result["replies"]), 3, text)
                self.assertGreaterEqual(int(result["peer_requests"]), 3, text)
                for key in ("send_errors", "echo_errors", "invalid"):
                    self.assertEqual(int(result[key]), 0, text)

    def test_missing_gate_has_bounded_failure_without_transmitting(self):
        with tempfile.TemporaryDirectory() as directory:
            a, b = self.ports()
            proc, _, log = self.start(Path(directory), 1, a, b)
            self.assertEqual(proc.wait(timeout=13), 2)
            text = log.read_text()
            self.assertIn("start gate timed out", text)
            self.assertNotIn("started ", text)
            self.assertNotIn("result ", text)


if __name__ == "__main__":
    unittest.main()
