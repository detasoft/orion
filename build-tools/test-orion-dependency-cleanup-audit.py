#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


def artifact(name, scope="compile", version="1", children=(), group="example", classifier=""):
    return {"groupId": group, "artifactId": name, "version": version, "scope": scope,
            "type": "jar", "classifier": classifier, "children": list(children)}


class DependencyAuditTest(unittest.TestCase):
    def run_audit(self, dependencies, tree, child=False):
        script = Path(__file__).with_name("orion-dependency-cleanup-audit.py")
        with tempfile.TemporaryDirectory(prefix="orion-dependency-audit-") as directory:
            root = Path(directory) / "checkout with spaces"
            root.mkdir()
            module = root
            if child:
                (root / "pom.xml").write_text(
                    '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                    '<modules><module>child</module></modules></project>', encoding="utf-8")
                (root / "target").mkdir()
                (root / "target/dependency-audit.json").write_text(
                    json.dumps(artifact("parent")), encoding="utf-8")
                module = root / "child"
                module.mkdir()
            (module / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>'
                + dependencies + '</dependencies></project>', encoding="utf-8")
            if tree is not None:
                (module / "target").mkdir()
                (module / "target/dependency-audit.json").write_text(json.dumps(tree), encoding="utf-8")
            return subprocess.run([sys.executable, str(script), "--root", str(root)],
                                  capture_output=True, text=True)

    def test_reports_a_duplicate_with_its_transitive_path_in_a_child_module(self):
        dependency = '<dependency><groupId>example</groupId><artifactId>base</artifactId></dependency>'
        base = artifact("base")
        tree = artifact("child", children=[base, artifact("middle", children=[base])])
        result = self.run_audit(dependency, tree, child=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("child/pom.xml", result.stdout)
        self.assertIn("example:base", result.stdout)
        self.assertIn("middle -> base", result.stdout)
        self.assertIn("Candidates: 1", result.stdout)

    def test_distinguishes_a_test_path_from_a_provided_dependency(self):
        dependency = ('<dependency><groupId>example</groupId><artifactId>base</artifactId>'
                      '<scope>provided</scope></dependency>')
        tree = artifact("module", children=[artifact("base", "provided"),
                                             artifact("tests", "test", children=[artifact("base", "test")])])
        result = self.run_audit(dependency, tree)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("provided", result.stdout)
        self.assertIn("test", result.stdout)
        self.assertIn("Scope/version constraints: 1", result.stdout)
        self.assertIn("Candidates: 0", result.stdout)

    def test_does_not_count_a_different_classifier_as_the_same_dependency(self):
        dependency = '<dependency><groupId>example</groupId><artifactId>base</artifactId></dependency>'
        tree = artifact("module", children=[artifact("base"), artifact("middle", children=[
            artifact("base", classifier="tests")])])
        result = self.run_audit(dependency, tree)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Candidates: 0", result.stdout)

    def test_reports_a_version_override_without_calling_it_a_candidate(self):
        dependency = '<dependency><groupId>example</groupId><artifactId>base</artifactId></dependency>'
        tree = artifact("module", children=[artifact("base", version="2"),
                                             artifact("middle", children=[artifact("base", version="1")])])
        result = self.run_audit(dependency, tree)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Scope/version constraints: 1", result.stdout)
        self.assertIn("Candidates: 0", result.stdout)

    def test_marks_dependency_exclusions_for_manual_review(self):
        dependency = ('<dependency><groupId>example</groupId><artifactId>base</artifactId>'
                      '<exclusions><exclusion><groupId>example</groupId><artifactId>extra</artifactId>'
                      '</exclusion></exclusions></dependency>')
        base = artifact("base")
        tree = artifact("module", children=[base, artifact("middle", children=[base])])
        result = self.run_audit(dependency, tree)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("exclusions", result.stdout)
        self.assertIn("Candidates: 0", result.stdout)

    def test_fails_clearly_when_a_dependency_tree_is_missing(self):
        result = self.run_audit("", None)
        self.assertEqual(result.returncode, 2)
        self.assertIn("make dependency-audit", result.stderr)

    def test_applies_an_intermediate_direct_scope_override_to_the_transitive_path(self):
        dependency = ('<dependency><groupId>example</groupId><artifactId>base</artifactId></dependency>'
                      '<dependency><groupId>example</groupId><artifactId>common</artifactId>'
                      '<scope>test</scope></dependency>')
        common = artifact("common", children=[artifact("base")])
        tree = artifact("module", children=[artifact("base"), artifact("common", "test"),
                                             artifact("middle", children=[common])])
        result = self.run_audit(dependency, tree)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Candidates: 0", result.stdout)


if __name__ == "__main__":
    unittest.main()
