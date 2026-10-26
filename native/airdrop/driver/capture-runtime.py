#!/usr/bin/env python3
"""Stream kernel/system diagnostics to the computer across an Android reboot.

Read-only. The caller starts and stops any driver test separately. No buffer
clearing, device reboot, root configuration or driver changes are performed.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import shlex
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--duration", type=int, default=180)
    parser.add_argument("--kernel-reader", help="device path to the unbuffered native kmsg-reader")
    args = parser.parse_args()
    if not 1 <= args.duration <= 3600:
        parser.error("duration must be between 1 and 3600 seconds")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    stop = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    events_lock = threading.Lock()
    adb = ["adb", "-s", args.serial]

    def event(kind, **fields):
        record = {"time_utc": datetime.now(timezone.utc).isoformat(), "kind": kind, **fields}
        with events_lock, (output / "events.jsonl").open("a") as log:
            log.write(json.dumps(record) + "\n")
        print(json.dumps(record), flush=True)

    def available():
        try:
            result = subprocess.run(adb + ["get-state"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                    timeout=3)
            return result.returncode == 0 and result.stdout.strip() == b"device"
        except subprocess.TimeoutExpired:
            return False

    def stream(name, command):
        segment = 0
        while not stop.is_set():
            if not available():
                stop.wait(1)
                continue
            segment += 1
            # Files are unbuffered on the computer, so a phone reboot cannot
            # erase bytes already received from the previous kernel.
            with (output / f"{name}-{segment:03}.log").open("wb", buffering=0) as log:
                process = subprocess.Popen(adb + command, stdout=log, stderr=subprocess.STDOUT)
                event("stream_started", stream=name, segment=segment, host_pid=process.pid)
                while process.poll() is None and not stop.wait(0.2):
                    pass
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                process.wait()
                event("stream_ended", stream=name, segment=segment, status=process.returncode)
            stop.wait(1)

    event("capture_started", host_pid=os.getpid(), duration_seconds=args.duration)
    kernel_command = ["shell", "su -c " + shlex.quote(args.kernel_reader)] if args.kernel_reader else \
        ["shell", "-tt", "su -c 'dmesg -w'"]
    workers = [threading.Thread(target=stream, args=("kernel", kernel_command), daemon=True),
               threading.Thread(target=stream, args=("system", ["logcat", "-b", "system", "-b", "crash",
                                                               "-v", "threadtime", "-T", "1"]), daemon=True)]
    for worker in workers:
        worker.start()
    deadline = time.monotonic() + args.duration
    last = None
    try:
        while not stop.is_set() and time.monotonic() < deadline and not (output / "stop").exists():
            online = available()
            if online != last:
                event("device_state", online=online)
                last = online
            stop.wait(1)
    finally:
        stop.set()
        for worker in workers:
            worker.join(timeout=8)
        event("capture_finished", active_workers=[worker.name for worker in workers if worker.is_alive()])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
