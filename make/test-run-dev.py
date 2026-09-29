import json
import os
from pathlib import Path
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]


def signal_group(group, signum):
    try:
        os.killpg(group, signum)
    except (ProcessLookupError, PermissionError):
        pass


def service_double(role, worker=False):
    directory = Path(os.environ["DEV_TEST_STATE"])
    if worker:
        if os.environ.get("DEV_TEST_STUBBORN") == role:
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            (directory / f"{role}.json").write_text(json.dumps({
                "port": listener.getsockname()[1], "group": os.getpgrp(),
            }))
            while True:
                time.sleep(0.1)
    subprocess.Popen([sys.executable, __file__, "--worker", role])
    (directory / f"{role}-invocation.json").write_text(json.dumps({
        "cwd": os.getcwd(), "arguments": sys.argv[3:], "orion_root": os.environ.get("ORION_ROOT"),
    }))
    while not all((directory / f"{name}.json").exists() for name in ("server", "frontend")):
        time.sleep(0.05)
    while not (directory / "release").exists():
        time.sleep(0.05)
    if os.environ.get("DEV_TEST_FAIL") == role:
        sys.exit(23)
    while True:
        time.sleep(0.1)


class RunDevTest(unittest.TestCase):
    def exercise(self, fail=None, stubborn=None):
        with tempfile.TemporaryDirectory(prefix="orion-run-dev-test-") as directory:
            state = Path(directory)
            checkout = state / "checkout with spaces"
            (checkout / "net/frontend/ui").mkdir(parents=True)
            (checkout / "make").symlink_to(ROOT / "make", target_is_directory=True)
            (checkout / "Makefile").symlink_to(ROOT / "Makefile")
            executable = " ".join(shlex.quote(part) for part in [sys.executable, __file__, "--service"])
            environment = {**os.environ, "DEV_TEST_STATE": directory,
                           "DEV_TEST_FAIL": fail or "", "DEV_TEST_STUBBORN": stubborn or "",
                           "ORION_KEY_MATERIAL_PASSWORD": "synthetic-test-password",
                           "MAKEFLAGS": "", "MFLAGS": "", "MAKELEVEL": "0"}
            process = subprocess.Popen(
                ["make", "--no-print-directory", "-j2", "-f", str(ROOT / "Makefile"), "run-dev",
                 f"MAVEN={executable} server", f"NPM={executable} frontend",
                 "ORION_ARGS=--example", f"ORION_ROOT={state / 'server state'}"],
                cwd=checkout, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, start_new_session=True,
            )
            services = []
            try:
                deadline = time.monotonic() + 10
                while not all((state / f"{role}.json").exists() for role in ("server", "frontend")):
                    if process.poll() is not None or time.monotonic() > deadline:
                        self.fail("Both services did not start: " + process.communicate(timeout=2)[0])
                    time.sleep(0.05)
                services = [json.loads((state / f"{role}.json").read_text())
                            for role in ("server", "frontend")]
                server = json.loads((state / "server-invocation.json").read_text())
                frontend = json.loads((state / "frontend-invocation.json").read_text())
                self.assertIn("-Dorion.run.arguments=--example", server["arguments"])
                self.assertEqual(server["orion_root"], str(state / "server state"))
                self.assertEqual(Path(frontend["cwd"]).resolve(), (checkout / "net/frontend/ui").resolve())
                self.assertEqual(frontend["arguments"], ["run", "dev"])
                if fail:
                    (state / "release").touch()
                else:
                    signal_group(process.pid, signal.SIGINT)
                output = process.communicate(timeout=20)[0]
                self.assertNotEqual(process.returncode, 0, output)
                self.assertNotIn("jobserver unavailable", output)
                for service in services:
                    with socket.socket() as connection:
                        connection.settimeout(1)
                        self.assertNotEqual(connection.connect_ex(("127.0.0.1", service["port"])), 0,
                                            "A service descendant is still listening after run-dev stopped")
            finally:
                for role in ("server", "frontend"):
                    information = state / f"{role}.json"
                    if information.exists():
                        signal_group(json.loads(information.read_text())["group"], signal.SIGKILL)
                signal_group(process.pid, signal.SIGKILL)
                process.communicate(timeout=5)

    def test_ctrl_c_stops_both_services_and_their_descendants(self):
        self.exercise()

    def test_server_failure_stops_frontend_and_orphaned_server_descendant(self):
        self.exercise(fail="server")

    def test_frontend_failure_stops_server(self):
        self.exercise(fail="frontend")

    def test_ctrl_c_escalates_for_a_descendant_that_ignores_signals(self):
        self.exercise(stubborn="frontend")


if __name__ == "__main__":
    if sys.argv[1:2] == ["--service"]:
        service_double(sys.argv[2])
    elif sys.argv[1:2] == ["--worker"]:
        service_double(sys.argv[2], worker=True)
    else:
        unittest.main()
