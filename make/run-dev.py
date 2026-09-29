#!/usr/bin/env python3
"""Run the Orion server and frontend as one development session."""
import os
import signal
import subprocess
import sys
import time


def signal_group(process, signum):
    try:
        os.killpg(process.pid, signum)
        return True
    except ProcessLookupError:
        return False


def main():
    command = sys.argv[1:] or ["make"]
    processes = []
    stop_signal = 0

    def request_stop(signum, _frame):
        nonlocal stop_signal
        stop_signal = signum

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    print("Starting Orion and Vite; press Ctrl-C to stop both.", flush=True)
    try:
        for goal in ("run-server", "run-frontend"):
            if stop_signal:
                return 128 + stop_signal
            processes.append(subprocess.Popen(
                [*command, "--no-print-directory", goal], start_new_session=True,
                stdin=subprocess.DEVNULL, close_fds=False,
            ))
        while not stop_signal:
            for process in processes:
                code = process.poll()
                if code is not None:
                    return code if code >= 0 else 128 - code
            time.sleep(0.1)
        return 128 + stop_signal
    except OSError as error:
        print(f"Cannot start development services: {error}", file=sys.stderr)
        return 1
    finally:
        for process in processes:
            signal_group(process, stop_signal or signal.SIGTERM)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            for process in processes:
                process.poll()
            if not any(signal_group(process, 0) for process in processes):
                break
            time.sleep(0.05)
        for process in processes:
            signal_group(process, signal.SIGKILL)
        for process in processes:
            process.wait(timeout=5)


if __name__ == "__main__":
    sys.exit(main())
