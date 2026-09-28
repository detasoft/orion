#!/usr/bin/env python3
"""Serialize Maven calls in the current checkout without leaving stale locks."""
import fcntl
import os
from pathlib import Path
import sys


def main():
    if len(sys.argv) < 2:
        print("Usage: run-maven.py <maven-command> [arguments...]", file=sys.stderr)
        return 2

    lock_path = Path(".mvn/build.lock")
    lock_path.parent.mkdir(exist_ok=True)
    with lock_path.open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Waiting for Maven build lock...", file=sys.stderr, flush=True)
            fcntl.flock(lock, fcntl.LOCK_EX)
        os.set_inheritable(lock.fileno(), True)
        os.execvp(sys.argv[1], sys.argv[1:])


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
