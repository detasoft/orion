import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class MakeTest(unittest.TestCase):
    def invoke(self, *arguments, exit_code=0):
        with tempfile.TemporaryDirectory(prefix="orion-make-test-") as directory:
            trace = Path(directory) / "invocation.json"
            command = shlex.join([sys.executable, __file__, "--maven-double"])
            environment = {**os.environ, "TEST_TRACE": str(trace), "TEST_EXIT": str(exit_code),
                           "TMPDIR": directory, "JAVA_TOOL_OPTIONS": "-Dexisting=kept",
                           "MAKEFLAGS": "", "MFLAGS": ""}
            result = subprocess.run(["make", "--no-print-directory", "test", "MAVEN=" + command,
                                     *arguments], cwd=ROOT, env=environment, capture_output=True, text=True)
            invocation = json.loads(trace.read_text()) if trace.exists() else None
            if invocation:
                self.assertEqual(invocation["tmp"], directory)
                self.assertEqual(invocation["java"], "-Dexisting=kept")
            return result, invocation

    def test_full_suite(self):
        result, invocation = self.invoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(invocation["args"], ["package", "-Pdev", "-T", "4", "-q"])

    def test_focused_suite_with_log_path_containing_spaces(self):
        result, invocation = self.invoke("MODULE=core/example", "TEST=ExampleTest#works", "LOG=/tmp/test log")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(invocation["args"], ["package", "-Pdev", "-T", "4", "-q", "-pl",
                         "core/example", "-am", "-Dtest=ExampleTest#works",
                         "-Dsurefire.failIfNoSpecifiedTests=false", "-l", "/tmp/test log"])

    def test_rejects_incomplete_selection(self):
        for argument in ("MODULE=core/example", "TEST=ExampleTest"):
            with self.subTest(argument=argument):
                result, invocation = self.invoke(argument)
                self.assertNotEqual(result.returncode, 0)
                self.assertIsNone(invocation)
                self.assertIn("Set both MODULE and TEST", result.stderr)

    def test_propagates_maven_failure(self):
        result, invocation = self.invoke(exit_code=23)
        self.assertIsNotNone(invocation)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Error 23", result.stderr)


class RunGoalTest(unittest.TestCase):
    def test_positional_server_and_agent_preserve_arguments(self):
        for service, option in (("server", "ORION_ARGS=--example"), ("agent", "AGENT_ARGS=--example")):
            with self.subTest(service=service), tempfile.TemporaryDirectory(prefix="orion-run-goal-") as directory:
                trace = Path(directory) / "invocation.json"
                command = shlex.join([sys.executable, __file__, "--maven-double"])
                result = subprocess.run(
                    ["make", "--no-print-directory", "run", service, "MAVEN=" + command, option],
                    cwd=ROOT, capture_output=True, text=True,
                    env={**os.environ, "ORION_KEY_MATERIAL_PASSWORD": "test-only",
                         "ORION_DEV_RUN_DIR": directory, "TEST_TRACE": str(trace), "TEST_EXIT": "0",
                         "MAKEFLAGS": "", "MFLAGS": ""})
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                arguments = json.loads(trace.read_text())["args"]
                property_name = "orion" if service == "server" else "agentd"
                self.assertIn(f"-D{property_name}.run.arguments=--example", arguments)
                self.assertEqual(arguments[-1], "process-classes")
                self.assertFalse((Path(directory) / f"{service}.pid").exists())

    def test_invalid_component_or_extra_goal_fails_before_running_commands(self):
        for arguments in (["run"], ["run", "unknown"], ["run", "server", "test"], ["run", "server%"]):
            with self.subTest(arguments=arguments):
                result = subprocess.run(["make", *arguments, "MAVEN=false"], cwd=ROOT,
                                        capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue("Usage:" in result.stderr or "Unknown component" in result.stderr)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--maven-double"]:
        Path(os.environ["TEST_TRACE"]).write_text(json.dumps({
            "args": sys.argv[2:], "tmp": os.environ.get("TMPDIR"),
            "java": os.environ.get("JAVA_TOOL_OPTIONS"),
        }))
        sys.exit(int(os.environ["TEST_EXIT"]))
    unittest.main()
