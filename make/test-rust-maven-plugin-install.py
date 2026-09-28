#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest


class RustMavenPluginInstallTest(unittest.TestCase):
    def check_install(self, maven_exit):
        makefile = Path(__file__).resolve().parents[1] / "Makefile"
        with tempfile.TemporaryDirectory(prefix="orion-plugin-test-") as directory:
            root = Path(directory)
            checkout = root / "checkout with spaces"
            checkout.mkdir()
            temporary = root / "temporary files"
            temporary.mkdir()
            plugin = checkout / "build-tools/rust-maven-plugin"
            plugin.mkdir(parents=True)
            pom = plugin / "pom.xml"
            pom.write_text("released sources", encoding="utf-8")
            (checkout / "make").mkdir()
            (checkout / "make/server.mk").touch()
            (checkout / "make/run-maven.py").symlink_to(makefile.parent / "make/run-maven.py")
            (checkout / ".gitignore").write_text("/.mvn/build.lock\n", encoding="utf-8")

            def git(*arguments):
                return subprocess.run(
                    ["git", *arguments], cwd=checkout, check=True,
                    capture_output=True, text=True,
                ).stdout

            git("init", "--quiet")
            git("add", ".")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "fixture")
            git("tag", "rust-maven-plugin-0.1.0")
            pom.write_text("development sources", encoding="utf-8")
            git("add", ".")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "development")
            pom.write_text("local changes", encoding="utf-8")
            head = git("rev-parse", "HEAD")
            status = git("status", "--porcelain")
            trace = root / "maven.json"
            environment = dict(os.environ, TMPDIR=str(temporary), MAVEN_TRACE=str(trace),
                               MAVEN_EXIT=str(maven_exit))
            maven = " ".join(shlex.quote(part) for part in
                             [sys.executable, str(Path(__file__).resolve()), "--maven-double"])

            result = subprocess.run(
                ["make", "--no-print-directory", "-f", str(makefile),
                 "rust-maven-plugin-install", "MAVEN=" + maven],
                cwd=checkout, env=environment, capture_output=True, text=True,
            )

            self.assertEqual(result.returncode == 0, maven_exit == 0, result.stdout + result.stderr)
            self.assertTrue(trace.is_file(), "Maven was not invoked")
            invocation = json.loads(trace.read_text(encoding="utf-8"))
            self.assertEqual(invocation["arguments"][:2], ["install", "-f"])
            self.assertEqual(invocation["sources"], "released sources")
            self.assertEqual(Path(invocation["cwd"]).resolve(), checkout.resolve())
            self.assertEqual(git("rev-parse", "HEAD"), head)
            self.assertEqual(git("status", "--porcelain"), status)
            self.assertEqual(list(temporary.iterdir()), [])
            if maven_exit:
                self.assertIn("Error " + str(maven_exit), result.stderr)

    def test_installs_tagged_sources_without_changing_checkout(self):
        self.check_install(0)

    def test_cleans_temporary_sources_and_propagates_maven_failure(self):
        self.check_install(23)

    def test_rejects_install_goal_as_a_positional_test_argument(self):
        result = subprocess.run(
            ["make", "--no-print-directory", "run-test", "rust-maven-plugin-install", "placeholder"],
            cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Positional arguments cannot match Make goals", result.stderr)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--maven-double"]:
        arguments = sys.argv[2:]
        pom = Path(arguments[arguments.index("-f") + 1])
        (pom.parent / "target").mkdir()
        Path(os.environ["MAVEN_TRACE"]).write_text(json.dumps({
            "arguments": arguments, "sources": pom.read_text(encoding="utf-8"), "cwd": os.getcwd(),
        }), encoding="utf-8")
        sys.exit(int(os.environ["MAVEN_EXIT"]))
    unittest.main()
