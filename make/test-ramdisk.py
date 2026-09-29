#!/usr/bin/env python3
"""Give make test a private macOS RAM disk for temporary files."""
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
import uuid


def signal_group(process, signum):
    try:
        os.killpg(process.pid, signum)
        return True
    except ProcessLookupError:
        return False


def main():
    command = sys.argv[1:]
    if not command:
        print("Usage: test-ramdisk.py <command> [arguments...]", file=sys.stderr)
        return 2
    if sys.platform != "darwin":
        os.execvpe(command[0], command, os.environ)

    device = None
    process = None
    alias = None
    stop_signal = 0
    status = 1

    def request_stop(signum, _frame):
        nonlocal stop_signal
        stop_signal = signum

    previous_handlers = {sig: signal.signal(sig, request_stop) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        attached = subprocess.run(["hdiutil", "attach", "-nomount", "ram://4194304"],
                                  check=True, capture_output=True, text=True, start_new_session=True)
        device = attached.stdout.strip()
        if not re.fullmatch(r"/dev/disk[0-9]+", device):
            raise ValueError(f"Unexpected RAM device: {device!r}")
        volume = "OrionTest-" + uuid.uuid4().hex
        if not stop_signal:
            subprocess.run(["diskutil", "eraseDisk", "APFS", volume, device],
                           check=True, start_new_session=True)
        if not stop_signal:
            # Keep socket paths short and retain the usual macOS temporary-path symlink boundary.
            alias = tempfile.TemporaryDirectory(prefix="orion-test-", dir="/tmp")
            temporary = alias.name + "/ram"
            os.symlink("/Volumes/" + volume, temporary)
            environment = dict(os.environ)
            environment["TMPDIR"] = temporary
            options = environment.get("JAVA_TOOL_OPTIONS", "")
            environment["JAVA_TOOL_OPTIONS"] = (options + " -Djava.io.tmpdir=" + temporary).strip()
            print(f"Test temporary files: {temporary} (2 GiB RAM disk)", flush=True)
            process = subprocess.Popen(command, env=environment, start_new_session=True)
            while not stop_signal and process.poll() is None:
                time.sleep(0.1)
            if process.returncode is not None:
                status = process.returncode if process.returncode >= 0 else 128 - process.returncode
        if stop_signal:
            status = 128 + stop_signal
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Cannot run tests with RAM disk: {error}", file=sys.stderr)
    finally:
        if process is not None:
            signal_group(process, stop_signal or signal.SIGTERM)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                process.poll()
                if not signal_group(process, 0):
                    break
                time.sleep(0.05)
            signal_group(process, signal.SIGKILL)
            process.wait()
        if device is not None and re.fullmatch(r"/dev/disk[0-9]+", device):
            try:
                subprocess.run(["hdiutil", "detach", device], check=True, start_new_session=True)
            except (OSError, subprocess.CalledProcessError) as error:
                print(f"Cannot detach test RAM disk {device}: {error}", file=sys.stderr)
                status = status or 1
        if alias is not None:
            alias.cleanup()
        for sig, handler in previous_handlers.items():
            signal.signal(sig, handler)
    return status


if __name__ == "__main__":
    sys.exit(main())
