#!/usr/bin/env python3
"""Serialize Maven's npm install/build pair within one frontend directory."""

import fcntl
import os
from pathlib import Path
import subprocess
import sys


def main():
    directory = Path(__file__).resolve().parent
    node_directory = Path(sys.argv[1]).resolve()
    output_directory = Path(sys.argv[2]).resolve()
    environment = os.environ.copy()
    environment["PATH"] = str(node_directory) + os.pathsep + environment.get("PATH", "")
    npm = [str(node_directory / "node"), str(node_directory / "node_modules/npm/bin/npm-cli.js")]

    # Lock the source directory itself: no stale lock file or deletion during Maven clean.
    lock = os.open(directory, os.O_RDONLY)
    try:
        print("Waiting for the frontend build lock...", flush=True)
        fcntl.flock(lock, fcntl.LOCK_EX)
        for arguments in (
            ["ci", "--no-audit", "--no-fund"],
            ["run", "build", "--", "--outDir", str(output_directory)],
        ):
            # Children retain the lock if this wrapper is terminated before they exit.
            result = subprocess.run(npm + arguments, cwd=directory, env=environment, pass_fds=(lock,))
            if result.returncode:
                return result.returncode if result.returncode > 0 else 128 - result.returncode
        return 0
    finally:
        os.close(lock)


if __name__ == "__main__":
    sys.exit(main())
