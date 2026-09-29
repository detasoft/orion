#!/usr/bin/env python3
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]


def double():
    role = Path(sys.argv[0]).name
    state = Path(os.environ["RAM_TEST_STATE"])
    record = {"role": role, "args": sys.argv[1:], "tmp": os.environ.get("TMPDIR"),
              "resolved_tmp": str(Path(os.environ["TMPDIR"]).resolve()),
              "java": os.environ.get("JAVA_TOOL_OPTIONS")}
    with (state / "events").open("a") as output:
        output.write(json.dumps(record) + "\n")
    if role == "hdiutil" and sys.argv[1] == "attach":
        print("/dev/disk999")
    if role == "hdiutil" and sys.argv[1] == "detach" and os.environ.get("RAM_TEST_DETACH_FAIL"):
        sys.exit(19)
    if role == "diskutil" and os.environ.get("RAM_TEST_FORMAT_FAIL"):
        sys.exit(17)
    if role == "fake-maven":
        if os.environ.get("RAM_TEST_WAIT"):
            def stop(signum, _frame):
                (state / "stopped").write_text(str(signum))
                sys.exit(128 + signum)
            signal.signal(signal.SIGINT, stop)
            signal.signal(signal.SIGTERM, stop)
            (state / "ready").touch()
            while True:
                time.sleep(0.05)
        sys.exit(int(os.environ.get("RAM_TEST_EXIT", "0")))


@unittest.skipUnless(sys.platform == "darwin", "macOS RAM disk lifecycle")
class TestRamDisk(unittest.TestCase):
    def exercise(self, *, exit_code=0, format_fail=False, detach_fail=False, interrupt=False,
                 goal="test", other_platform=False):
        with tempfile.TemporaryDirectory(prefix="orion-ram-test-") as directory:
            state = Path(directory)
            for name in ("hdiutil", "diskutil", "fake-maven"):
                executable = state / name
                executable.write_text(Path(__file__).read_text())
                executable.chmod(0o755)
            environment = {**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"],
                           "RAM_TEST_STATE": directory, "JAVA_TOOL_OPTIONS": "-Dexisting.option=kept",
                           "RAM_TEST_EXIT": str(exit_code), "RAM_TEST_FORMAT_FAIL": "1" if format_fail else "",
                           "RAM_TEST_DETACH_FAIL": "1" if detach_fail else "",
                           "RAM_TEST_WAIT": "1" if interrupt else "", "TMPDIR": directory,
                           "MAKEFLAGS": "", "MFLAGS": "", "MAKELEVEL": "0"}
            command = ["make", "--no-print-directory", goal, "MAVEN=fake-maven"]
            if goal == "run-test":
                command.extend(["MODULE=example", "TEST=ExampleTest"])
            if other_platform:
                command = [sys.executable, "-c",
                           "import runpy,sys; sys.platform='linux'; "
                           "sys.argv=['make/test-ramdisk.py','fake-maven']; "
                           "runpy.run_path('make/test-ramdisk.py',run_name='__main__')"]
            process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, start_new_session=True)
            try:
                if interrupt:
                    deadline = time.monotonic() + 10
                    while not (state / "ready").exists():
                        if process.poll() is not None or time.monotonic() > deadline:
                            self.fail("Test command did not start")
                        time.sleep(0.02)
                    os.killpg(process.pid, interrupt)
                output = process.communicate(timeout=20)[0]
                events = [json.loads(line) for line in (state / "events").read_text().splitlines()]
                if goal == "run-test" or other_platform:
                    self.assertEqual([e["role"] for e in events], ["fake-maven"], output)
                    self.assertEqual(events[0]["tmp"], directory)
                    self.assertEqual(events[0]["java"], "-Dexisting.option=kept")
                    return
                self.assertEqual(events[0]["role"], "hdiutil", output)
                self.assertEqual(events[-1]["args"], ["detach", "/dev/disk999"], output)
                maven = [e for e in events if e["role"] == "fake-maven"]
                if format_fail:
                    self.assertEqual(maven, [])
                else:
                    self.assertEqual(len(maven), 1)
                    volume = next(e["args"][2] for e in events if e["role"] == "diskutil")
                    self.assertEqual(next(e["args"][:2] for e in events if e["role"] == "diskutil"),
                                     ["eraseDisk", "APFS"])
                    self.assertEqual(maven[0]["resolved_tmp"], "/Volumes/" + volume)
                    self.assertIn("-Djava.io.tmpdir=" + maven[0]["tmp"], maven[0]["java"])
                    self.assertFalse(Path(maven[0]["tmp"]).is_symlink())
                    self.assertIn("-Dexisting.option=kept", maven[0]["java"])
                    self.assertEqual(maven[0]["args"], ["package", "-Pdev", "-T", "4", "-q"])
                self.assertEqual(process.returncode == 0,
                                 not (exit_code or format_fail or detach_fail or interrupt), output)
                if interrupt:
                    self.assertTrue((state / "stopped").exists(), output)
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()

    def test_success_preserves_options_and_cleans_up(self):
        self.exercise()

    def test_failed_test_command_cleans_up(self):
        self.exercise(exit_code=23)

    def test_failed_format_cleans_up_without_starting_tests(self):
        self.exercise(format_fail=True)

    def test_interrupt_stops_command_before_cleanup(self):
        self.exercise(interrupt=signal.SIGINT)

    def test_termination_stops_command_before_cleanup(self):
        self.exercise(interrupt=signal.SIGTERM)

    def test_cleanup_failure_is_reported(self):
        self.exercise(detach_fail=True)

    def test_focused_tests_keep_original_environment(self):
        self.exercise(goal="run-test")

    def test_other_platform_keeps_original_environment(self):
        self.exercise(other_platform=True)


if __name__ == "__main__":
    if Path(sys.argv[0]).name in ("hdiutil", "diskutil", "fake-maven"):
        double()
    else:
        unittest.main()
