import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
VNC_URL = "http://localhost:6080/vnc.html?autoconnect=1"


class BrowserObserveTest(unittest.TestCase):
    def run_goals(self, observe="1", url="http://localhost:8000", opens=True):
        with tempfile.TemporaryDirectory(prefix="orion-observe-test-") as directory:
            temporary = Path(directory)
            events = temporary / "events.jsonl"
            events.write_text("")
            (temporary / "webbrowser.py").write_text(
                "import json, os\n"
                "def open(url, **kwargs):\n"
                "    with open_log() as log: log.write(json.dumps(['open', url]) + '\\n')\n"
                "    return os.environ['TEST_BROWSER_OPENS'] == '1'\n"
                "def open_log():\n"
                "    import builtins\n"
                "    return builtins.open(os.environ['TEST_BROWSER_EVENTS'], 'a')\n"
            )
            npm = temporary / "npm"
            npm.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "with open(os.environ['TEST_BROWSER_EVENTS'], 'a') as log:\n"
                "    log.write(json.dumps(['npm', *sys.argv[1:]]) + '\\n')\n"
            )
            npm.chmod(0o755)
            result = subprocess.run(
                ["make", "-s", "browser-test", "browser-acme-test", f"URL={url}",
                 f"OBSERVE={observe}", f"NPM={npm}", "TEST=local repository"],
                cwd=ROOT, text=True, capture_output=True, timeout=15,
                env={**os.environ, "PYTHONPATH": str(temporary),
                     "TEST_BROWSER_EVENTS": str(events), "TEST_BROWSER_OPENS": str(int(opens)),
                     "MAKEFLAGS": "", "MFLAGS": "", "MAKELEVEL": "0"},
            )
            return result, [json.loads(line) for line in events.read_text().splitlines()]

    def test_observe_opens_vnc_once_before_both_test_goals(self):
        result, events = self.run_goals()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(events, [
            ["open", VNC_URL],
            ["npm", "test", "--", "--grep", "local repository"],
            ["npm", "run", "test:acme", "--", "--grep", "local repository"],
        ])

    def test_fast_mode_does_not_open_a_browser(self):
        result, events = self.run_goals(observe="0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([event[0] for event in events], ["npm", "npm"])

    def test_unavailable_desktop_prints_url_and_continues_tests(self):
        result, events = self.run_goals(opens=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([event[0] for event in events], ["open", "npm", "npm"])
        self.assertIn("Open noVNC manually: " + VNC_URL, result.stdout + result.stderr)

    def test_invalid_arguments_do_not_open_browser_or_run_tests(self):
        for arguments in ({"url": ""}, {"observe": "invalid"}):
            with self.subTest(arguments=arguments):
                result, events = self.run_goals(**arguments)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(events, [])


if __name__ == "__main__":
    unittest.main()
