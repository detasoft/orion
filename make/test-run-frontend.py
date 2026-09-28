#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


class RunFrontendTest(unittest.TestCase):
    def run_frontend(self, npm_exit=0, target_file=False):
        repository = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="orion-frontend-test-") as directory:
            root = Path(directory)
            checkout = root / "checkout with spaces"
            frontend = checkout / "net/frontend/ui"
            frontend.mkdir(parents=True)
            (checkout / "make").symlink_to(repository / "make", target_is_directory=True)
            if target_file:
                (checkout / "run-frontend").touch()
            trace = root / "npm.json"
            environment = dict(os.environ, NPM_TRACE=str(trace), NPM_EXIT=str(npm_exit))
            npm = " ".join(shlex.quote(part) for part in
                           [sys.executable, str(Path(__file__).resolve()), "--npm-double"])

            result = subprocess.run(
                ["make", "--no-print-directory", "-f", str(repository / "Makefile"),
                 "run-frontend", "NPM=" + npm],
                cwd=checkout, env=environment, capture_output=True, text=True,
            )

            self.assertEqual(result.returncode == 0, npm_exit == 0, result.stdout + result.stderr)
            self.assertTrue(trace.is_file(), "npm was not invoked")
            invocation = json.loads(trace.read_text(encoding="utf-8"))
            self.assertEqual(invocation["arguments"], ["run", "dev"])
            self.assertEqual(Path(invocation["cwd"]).resolve(), frontend.resolve())
            if npm_exit:
                self.assertIn("Error " + str(npm_exit), result.stderr)

    def test_runs_the_development_script_in_the_frontend_directory(self):
        self.run_frontend()

    def test_propagates_npm_failure(self):
        self.run_frontend(npm_exit=23)

    def test_runs_even_when_a_file_matches_the_goal_name(self):
        self.run_frontend(target_file=True)

    def test_rejects_frontend_goal_as_a_positional_test_argument(self):
        result = subprocess.run(
            ["make", "--no-print-directory", "run-test", "run-frontend", "placeholder", "MAVEN=false"],
            cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Positional arguments cannot match Make goals", result.stderr)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--npm-double"]:
        Path(os.environ["NPM_TRACE"]).write_text(json.dumps({
            "arguments": sys.argv[2:], "cwd": os.getcwd(),
        }), encoding="utf-8")
        sys.exit(int(os.environ["NPM_EXIT"]))
    unittest.main()
