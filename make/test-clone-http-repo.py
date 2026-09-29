#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class CloneHttpRepoTest(unittest.TestCase):
    def invoke(self, arguments, ssh_exit=0, git_exit=0):
        repository = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="orion-clone-http-") as directory:
            root = Path(directory)
            for command in ("ssh", "git"):
                executable = root / command
                executable.write_text(
                    f"#!{sys.executable}\n"
                    "import json, os, sys\n"
                    "from pathlib import Path\n"
                    "command = Path(sys.argv[0]).name\n"
                    "with open(os.environ['TRACE'], 'a') as trace:\n"
                    "    trace.write(json.dumps({'command': command, 'arguments': sys.argv[1:], "
                    "'header': os.environ.get('ORION_AUTH_HEADER')}) + '\\n')\n"
                    "if command == 'ssh': print('test-token')\n"
                    "sys.exit(int(os.environ[command.upper() + '_EXIT']))\n",
                    encoding="utf-8",
                )
                executable.chmod(0o755)
            trace = root / "trace.jsonl"
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                               TRACE=str(trace), SSH_EXIT=str(ssh_exit), GIT_EXIT=str(git_exit))
            result = subprocess.run(
                ["make", "--no-print-directory", "clone-http-repo", *arguments,
                 "ORION_HTTP_HOST=example.test", "ORION_HTTP_PORT=8123"],
                cwd=repository, env=environment, capture_output=True, text=True,
            )
            calls = [json.loads(line) for line in trace.read_text().splitlines()] if trace.exists() else []
            return result, calls

    def test_normalizes_repository_urls_and_passes_token_in_environment(self):
        for name in ("project", "project.git", "team/project", "team/project.git"):
            with self.subTest(name=name):
                result, calls = self.invoke([name])
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual(["ssh", "git"], [call["command"] for call in calls])
                self.assertEqual(["issue-token", "3600"], calls[0]["arguments"][-2:])
                expected = name if name.endswith(".git") else name + ".git"
                self.assertEqual(
                    ["--config-env=http.extraHeader=ORION_AUTH_HEADER", "clone",
                     "http://example.test:8123/r/" + expected], calls[1]["arguments"],
                )
                self.assertEqual("Authorization: Bearer test-token", calls[1]["header"])

    def test_token_failure_prevents_clone(self):
        result, calls = self.invoke(["project"], ssh_exit=23)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["ssh"], [call["command"] for call in calls])

    def test_git_failure_is_reported(self):
        result, calls = self.invoke(["project"], git_exit=24)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["ssh", "git"], [call["command"] for call in calls])

    def test_invalid_argument_count_does_not_issue_token(self):
        for arguments in ([], ["one", "two"]):
            with self.subTest(arguments=arguments):
                result, calls = self.invoke(arguments)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Usage: make clone-http-repo", result.stderr)
                self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
