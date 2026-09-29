#!/usr/bin/env python3
import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "dependency_minimize", Path(__file__).with_name("orion-dependency-minimize.py"))
MINIMIZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MINIMIZER)


class DependencyMinimizeTest(unittest.TestCase):
    def write_effective_models(self, root, changes=None):
        document = MINIMIZER.AUDIT.ET.Element("projects")
        for path, model in MINIMIZER.AUDIT.module_poms(root / "pom.xml"):
            tree = json.loads((path.parent / "target/dependency-audit.json").read_text())
            for field in ("groupId", "artifactId", "version"):
                element = model.find("m:" + field, MINIMIZER.AUDIT.NS)
                if element is None:
                    element = MINIMIZER.AUDIT.ET.SubElement(model, "{" + MINIMIZER.AUDIT.NS["m"] + "}" + field)
                element.text = tree[field]
            if changes and tree["artifactId"] in changes:
                changes[tree["artifactId"]](model)
            document.append(model)
        MINIMIZER.AUDIT.ET.ElementTree(document).write(
            root / "target/dependency-effective-pom.xml", encoding="utf-8")

    def minimize(self, graph, main, tests=None, movable=None):
        return MINIMIZER.minimize(graph, main, tests or {}, set(graph) if movable is None else movable)

    def test_moves_a_dependency_from_an_unused_intermediate_to_its_consumer(self):
        graph = {"a": {"b": "compile"}, "b": {"c": "compile"}, "c": {}}
        result, moves = self.minimize(graph, {"a": {"b", "c"}, "b": set(), "c": set()})
        self.assertEqual(result, {"a": {"b": "compile", "c": "compile"}, "b": {}, "c": {}})
        self.assertEqual(len(moves), 1)
        self.assertEqual(graph["b"], {"c": "compile"})

    def test_keeps_a_dependency_used_by_the_intermediate_module(self):
        graph = {"a": {"b": "compile"}, "b": {"c": "compile"}, "c": {}}
        result, moves = self.minimize(graph, {"a": {"b", "c"}, "b": {"c"}})
        self.assertEqual(result, graph)
        self.assertEqual(moves, [])

    def test_keeps_test_access_in_the_donor_without_exporting_it(self):
        graph = {"a": {"b": "compile"}, "b": {"c": "compile"}, "c": {}}
        result, moves = self.minimize(graph, {"a": {"b", "c"}}, {"b": {"c"}})
        self.assertEqual(result["b"], {"c": "test"})
        self.assertEqual(result["a"], {"b": "compile", "c": "compile"})
        self.assertEqual(len(moves), 1)

    def test_reuses_the_nearest_real_consumer_for_more_distant_consumers(self):
        graph = {"a": {"middle": "compile"}, "middle": {"b": "compile"},
                 "b": {"c": "compile"}, "c": {}}
        result, moves = self.minimize(graph, {"a": {"middle", "c"}, "middle": {"b", "c"}})
        self.assertEqual(result["middle"], {"b": "compile", "c": "compile"})
        self.assertEqual(result["a"], {"middle": "compile"})
        self.assertEqual(len(moves), 1)

    def test_nearest_consumer_precedes_a_farther_consumer_with_a_scope_override(self):
        graph = {"a": {"middle": "compile", "h": "test"},
                 "middle": {"b": "compile", "h": "compile"}, "b": {"c": "compile"}, "h": {}, "c": {}}
        result, moves = self.minimize(graph, {"a": {"middle", "c"}, "middle": {"b", "h", "c"},
                                             "b": set(), "c": set(), "h": set()})
        self.assertEqual(result["a"], {"middle": "compile", "h": "test"})
        self.assertEqual(result["middle"], {"b": "compile", "h": "compile", "c": "compile"})
        self.assertEqual(len(moves), 1)

    def test_preserves_an_intermediate_needed_for_its_transitive_api(self):
        graph = {"b": {"c": "compile"}, "c": {"d": "compile"}, "d": {}}
        result, moves = self.minimize(graph, {"b": {"d"}, "c": {"d"}})
        self.assertEqual(result, graph)
        self.assertEqual(moves, [])

    def test_adds_a_test_dependency_when_only_the_consumers_tests_need_it(self):
        graph = {"a": {"b": "compile"}, "b": {"c": "compile"}, "c": {}}
        result, moves = self.minimize(graph, {"a": {"b"}}, {"a": {"c"}})
        self.assertEqual(result["a"], {"b": "compile", "c": "test"})
        self.assertEqual(result["b"], {})
        self.assertEqual(len(moves), 1)

    def test_keeps_runtime_and_provided_contracts(self):
        graph = {"a": {"b": "runtime", "c": "provided"}, "b": {}, "c": {}}
        result, moves = self.minimize(graph, {})
        self.assertEqual(result, graph)
        self.assertEqual(moves, [])

    def test_keeps_modules_without_bytecode_evidence(self):
        graph = {"b": {"c": "compile"}, "c": {}}
        result, moves = self.minimize(graph, {}, movable={"c"})
        self.assertEqual(result, graph)
        self.assertEqual(moves, [])

    def test_honors_a_direct_scope_override_of_a_transitive_dependency(self):
        graph = {"a": {"b": "compile", "c": "test"}, "b": {"c": "compile"}, "c": {}}
        self.assertNotIn("c", MINIMIZER.available(graph, "a"))
        self.assertIn("c", MINIMIZER.available(graph, "a", tests=True))

    def test_renders_the_move_without_losing_comments_or_an_existing_test_dependency(self):
        text = ('<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
                '    <dependencies>\n        <!-- kept -->\n        <dependency>\n'
                '            <groupId>example</groupId>\n            <artifactId>b</artifactId>\n'
                '        </dependency>\n    </dependencies>\n</project>\n')
        result = MINIMIZER.render_pom(text, {"b": "compile"}, {"b": "test", "c": "compile"},
                                     {"b": ("example", "1"), "c": ("example", "1")})
        self.assertIn("<!-- kept -->", result)
        pom = MINIMIZER.AUDIT.ET.fromstring(result)
        dependencies = pom.findall("m:dependencies/m:dependency", MINIMIZER.AUDIT.NS)
        scopes = {node.findtext("m:artifactId", namespaces=MINIMIZER.AUDIT.NS):
                  node.findtext("m:scope", default="compile", namespaces=MINIMIZER.AUDIT.NS)
                  for node in dependencies}
        self.assertEqual(scopes, {"b": "test", "c": "compile"})

    def test_can_add_a_direct_dependency_to_a_consumer_with_only_inherited_dependencies(self):
        text = '<project xmlns="http://maven.apache.org/POM/4.0.0">\n</project>\n'
        result = MINIMIZER.render_pom(text, {"c": "test"}, {"c": "compile"}, {"c": ("example", "1")})
        pom = MINIMIZER.AUDIT.ET.fromstring(result)
        dependencies = pom.findall("m:dependencies/m:dependency", MINIMIZER.AUDIT.NS)
        self.assertEqual(len(dependencies), 1)
        self.assertEqual(dependencies[0].findtext("m:artifactId", namespaces=MINIMIZER.AUDIT.NS), "c")

    def test_previews_and_applies_a_patch_using_real_bytecode(self):
        with tempfile.TemporaryDirectory(prefix="orion-minimize-test-") as directory:
            root = Path(directory) / "checkout with spaces"
            root.mkdir()
            node = lambda name, children=(): {"groupId": "example", "artifactId": name, "version": "1",
                                              "scope": "compile", "children": list(children)}
            trees = {"root": node("root"), "a": node("a", [node("b", [node("c")])]),
                     "b": node("b", [node("c")]), "c": node("c")}
            declarations = {"root": "", "a": "b", "b": "c", "c": ""}
            sources = []
            for name in trees:
                module = root if name == "root" else root / name
                (module / "target").mkdir(parents=True)
                dependency = (f'<dependency>\n            <groupId>example</groupId>\n'
                              f'            <artifactId>{declarations[name]}</artifactId>\n'
                              '        </dependency>\n') if declarations[name] else ""
                modules = '<modules><module>a</module><module>b</module><module>c</module></modules>'
                (module / "pom.xml").write_text(
                    '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
                    + (modules + '\n' if name == "root" else '')
                    + '    <dependencies>\n        ' + dependency + '    </dependencies>\n</project>\n',
                    encoding="utf-8")
                (module / "target/dependency-audit.json").write_text(json.dumps(trees[name]), encoding="utf-8")
                if name != "root":
                    source = root / (name.upper() + ".java")
                    fields = 'public B b = new B(); public C c = new C();' if name == "a" else ""
                    source.write_text(f'package fixture; public class {name.upper()} {{ {fields} }}',
                                      encoding="utf-8")
                    sources.append(str(source))
            subprocess.run(["javac", "-d", str(root / "compiled"), *sources], check=True, capture_output=True)
            for name in ("a", "b", "c"):
                classes = root / name / "target/classes/fixture"
                classes.mkdir(parents=True)
                shutil.copy(root / "compiled/fixture" / (name.upper() + ".class"), classes)
            multi_release = root / "multi-release.jar"
            with zipfile.ZipFile(multi_release, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMulti-Release: true\n\n")
                archive.write(root / "compiled/fixture/C.class", "META-INF/versions/21/fixture/C.class")
            classpath = os.pathsep.join(
                [*(str(root / name / "target/classes") for name in ("a", "b", "c")), str(multi_release)])
            for name in trees:
                module = root if name == "root" else root / name
                (module / "target/dependency-classpath.txt").write_text(classpath, encoding="utf-8")
            self.write_effective_models(root)
            command = [sys.executable, str(Path(__file__).with_name("orion-dependency-minimize.py")),
                       "--root", str(root)]
            preview = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(preview.returncode, 0, preview.stderr)
            self.assertIn("Dependency relocations: 1", preview.stderr)
            check = subprocess.run(["git", "apply", "--check", "-"], cwd=root,
                                   input=preview.stdout, capture_output=True, text=True)
            self.assertEqual(check.returncode, 0, check.stderr)
            applied = subprocess.run(command + ["--apply"], capture_output=True, text=True)
            self.assertEqual(applied.returncode, 0, applied.stderr)
            reverse = subprocess.run(["git", "apply", "--reverse", "--check", "-"], cwd=root,
                                     input=preview.stdout, capture_output=True, text=True)
            self.assertEqual(reverse.returncode, 0, reverse.stderr)

    def external_fixture(self, root, used=False, tests=False, constraint="", group="library", version="1"):
        def node(name, children=(), scope="compile", group="example", version="1"):
            return {"groupId": group, "artifactId": name, "version": version, "scope": scope,
                    "type": "jar", "children": list(children)}

        library = node("c", group=group, version=version)
        trees = {"root": node("root"), "a": node("a", [node("b", [library])]),
                 "b": node("b", [library])}
        for name, tree in trees.items():
            module = root if name == "root" else root / name
            (module / "target").mkdir(parents=True)
            dependency = ("example", "b", "") if name == "a" else (group, "c", constraint)
            dependencies = ""
            if name != "root":
                dependencies = (f'        <dependency>\n            <groupId>{dependency[0]}</groupId>\n'
                                f'            <artifactId>{dependency[1]}</artifactId>\n'
                                f'            {dependency[2]}\n        </dependency>\n')
            modules = '<modules><module>a</module><module>b</module></modules>\n' if name == "root" else ""
            (module / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">\n' + modules
                + '    <dependencies>\n' + dependencies + '    </dependencies>\n</project>\n')
            (module / "target/dependency-audit.json").write_text(json.dumps(tree))
        sources = {"Ext": "", "A": "public B b; public Ext ext;", "B": "public Ext ext;" if used else "",
                   "OwnTest": "public A own;" + ("public Ext ext;" if tests else "")}
        for name, body in sources.items():
            (root / (name + ".java")).write_text(f"package fixture; public class {name} {{ {body} }}")
        subprocess.run(["javac", "-d", str(root / "compiled"),
                        *(str(root / (name + ".java")) for name in sources)], check=True, capture_output=True)
        for module, name, folder in (("a", "A", "classes"), ("b", "B", "classes"),
                                     ("a", "OwnTest", "test-classes")):
            target = root / module / "target" / folder / "fixture"
            target.mkdir(parents=True)
            shutil.copy(root / "compiled/fixture" / (name + ".class"), target)
        jar = root / ("c-" + version + ".jar")
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMulti-Release: true\n\n")
            archive.write(root / "compiled/fixture/Ext.class", "META-INF/versions/21/fixture/Ext.class")
        for name in trees:
            module = root if name == "root" else root / name
            # Deliberately omit A's own classes, as Maven build-classpath does.
            classpath = [str(jar), str(root / "b/target/classes")]
            (module / "target/dependency-classpath.txt").write_text(os.pathsep.join(classpath))
        self.write_effective_models(root)
        return [sys.executable, "-B", str(Path(__file__).with_name("orion-dependency-minimize.py")),
                "--root", str(root)]

    def test_external_library_moves_with_real_multi_release_bytecode_and_own_test_classes(self):
        for used in (False, True):
            with self.subTest(intermediate_uses_library=used), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                command = self.external_fixture(root, used=used, tests=True)
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"Dependency relocations: {0 if used else 1}", result.stderr)
                if not used:
                    applied = subprocess.run(command + ["--apply"], capture_output=True, text=True)
                    self.assertEqual(applied.returncode, 0, applied.stderr)
                    dependencies = MINIMIZER.AUDIT.ET.parse(root / "a/pom.xml").getroot().findall(
                        "m:dependencies/m:dependency", MINIMIZER.AUDIT.NS)
                    self.assertIn(("library", "c", "1"), [tuple(dependency.findtext(
                        "m:" + field, namespaces=MINIMIZER.AUDIT.NS)
                        for field in ("groupId", "artifactId", "version")) for dependency in dependencies])

    def test_consumer_exclusions_prevent_dependency_relocation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            path = root / "a/pom.xml"
            path.write_text(path.read_text().replace("<artifactId>b</artifactId>",
                "<artifactId>b</artifactId><exclusions><exclusion><groupId>library</groupId>"
                "<artifactId>c</artifactId></exclusion></exclusions>"))
            self.write_effective_models(root)
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Dependency relocations: 0", result.stderr)

    def test_preserves_the_provided_scope_of_an_exported_library(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            pom = root / "a/pom.xml"
            pom.write_text(pom.read_text().replace(
                "<artifactId>b</artifactId>",
                "<artifactId>b</artifactId>\n            <scope>provided</scope>"))
            path = root / "a/target/dependency-audit.json"
            tree = json.loads(path.read_text())
            tree["children"][0]["scope"] = "provided"
            tree["children"][0]["children"][0]["scope"] = "provided"
            path.write_text(json.dumps(tree))
            self.write_effective_models(root)
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Dependency relocations: 0", result.stderr)

    def test_rendering_preserves_a_different_classifier_of_the_same_artifact(self):
        dependency = ('        <dependency>\n            <groupId>library</groupId>\n'
                      '            <artifactId>c</artifactId>\n{}        </dependency>\n')
        text = '<project>\n    <dependencies>\n' + dependency.format("") + dependency.format(
            '            <classifier>tests</classifier>\n') + '    </dependencies>\n</project>\n'
        result = MINIMIZER.render_pom(text, {"library:c:jar:": "compile"}, {},
                                     {"library:c:jar:": ("library", "1")})
        self.assertIn("<classifier>tests</classifier>", result)
        self.assertEqual(result.count("<artifactId>c</artifactId>"), 1)

    def test_adds_direct_dependencies_outside_dependency_management_and_profiles(self):
        text = ('<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
                '    <dependencyManagement>\n        <dependencies>\n        </dependencies>\n'
                '    </dependencyManagement>\n    <profiles><profile>\n'
                '        <dependencies>\n        </dependencies>\n    </profile></profiles>\n</project>\n')
        result = MINIMIZER.render_pom(text, {}, {"c": "compile"}, {"c": ("example", "1")})
        dependencies = MINIMIZER.AUDIT.ET.fromstring(result).findall(
            "m:dependencies/m:dependency", MINIMIZER.AUDIT.NS)
        self.assertEqual(len(dependencies), 1)

    def test_accepts_dependency_free_modules_without_a_classpath_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            (root / "target/dependency-classpath.txt").unlink()
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_external_archive_collisions_and_mixed_versions_prevent_relocation(self):
        for collision in (True, False):
            with self.subTest(collision=collision), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                command = self.external_fixture(root)
                path = root / "a/target/dependency-audit.json"
                tree = json.loads(path.read_text())
                alternate = {"groupId": "other" if collision else "library", "artifactId": "c",
                             "version": "1" if collision else "2", "type": "jar", "scope": "compile"}
                tree["children"].append({"groupId": "library", "artifactId": "remaining-path",
                                         "version": "1", "type": "jar", "scope": "compile",
                                         "children": [alternate]})
                path.write_text(json.dumps(tree))
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Dependency relocations: 0", result.stderr)

    def test_keeps_external_test_access_without_exporting_it_from_the_donor(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            source = root / "BTest.java"
            source.write_text("package fixture; public class BTest { public B own; public Ext ext; }")
            subprocess.run(["javac", "-cp", str(root / "compiled"), "-d",
                            str(root / "b/target/test-classes"), str(source)], check=True, capture_output=True)
            result = subprocess.run(command + ["--apply"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            dependency = MINIMIZER.AUDIT.ET.parse(root / "b/pom.xml").getroot().find(
                "m:dependencies/m:dependency", MINIMIZER.AUDIT.NS)
            self.assertEqual(dependency.findtext("m:scope", namespaces=MINIMIZER.AUDIT.NS), "test")

    def test_preserves_an_external_annotation_processor(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            with zipfile.ZipFile(root / "c-1.jar", "a") as archive:
                archive.writestr("META-INF/services/javax.annotation.processing.Processor", "fixture.Ext\n")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Dependency relocations: 0", result.stderr)

    def test_preserves_a_runtime_service_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            with zipfile.ZipFile(root / "c-1.jar", "a") as archive:
                archive.writestr("META-INF/services/fixture.RuntimeService", "fixture.Ext\n")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Dependency relocations: 0", result.stderr)

    def test_preserves_source_retained_annotation_apis_without_a_processor_service(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            annotation = root / "Marker.java"
            annotation.write_text("package fixture; @java.lang.annotation.Retention("
                                  "java.lang.annotation.RetentionPolicy.SOURCE) public @interface Marker {}")
            source = root / "b/src/main/java/fixture/B.java"
            source.parent.mkdir(parents=True)
            source.write_text("package fixture; @Marker public class B {}")
            subprocess.run(["javac", "-d", str(root / "compiled"), str(annotation), str(source)],
                           check=True, capture_output=True)
            shutil.copy(root / "compiled/fixture/B.class", root / "b/target/classes/fixture/B.class")
            with zipfile.ZipFile(root / "c-1.jar", "a") as archive:
                archive.write(root / "compiled/fixture/Marker.class", "fixture/Marker.class")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Dependency relocations: 0", result.stderr)

    def test_target_test_contracts_do_not_block_unrelated_export_relocation(self):
        graph = {"a": {"b": "compile"}, "b": {"c": "compile"},
                 "c": {"test-contract": "test"}, "test-contract": {}}
        result, moves = MINIMIZER.minimize(graph, {"a": {"b", "c"}, "b": set(), "c": set()}, {},
                                           set(graph), protected={"b": {"test-contract"}})
        self.assertEqual(result["b"], {})
        self.assertEqual(result["a"], {"b": "compile", "c": "compile"})
        self.assertEqual(len(moves), 1)

    def test_preserves_a_library_used_only_through_an_inlined_constant(self):
        for reference in ("import fixture.Constants; Constants.VALUE",
                          "import static fixture.Constants.*; VALUE",
                          "import fixture.*; Constants.VALUE", "fixture.Constants.VALUE", "Constants.VALUE"):
            with self.subTest(reference=reference), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                command = self.external_fixture(root)
                constants = root / "Constants.java"
                constants.write_text("package fixture; public class Constants { "
                                     "public static final int VALUE = 7; }")
                parts = reference.rsplit(";", 1)
                imported, expression = (parts[0] + ";", parts[1]) if len(parts) == 2 else ("", parts[0])
                source = root / "b/src/main/java/fixture/B.java"
                source.parent.mkdir(parents=True)
                source.write_text("package fixture; " + imported
                                  + " public class B { public char quote = '\"'; public int value = "
                                  + expression + '; public String text = "x"; }')
                subprocess.run(["javac", "-d", str(root / "compiled"), str(constants), str(source)],
                               check=True, capture_output=True)
                shutil.copy(root / "compiled/fixture/B.class", root / "b/target/classes/fixture/B.class")
                with zipfile.ZipFile(root / "c-1.jar", "a") as archive:
                    archive.write(root / "compiled/fixture/Constants.class", "fixture/Constants.class")
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Dependency relocations: 0", result.stderr)

    def test_source_compile_usage_survives_quote_literals(self):
        bodies = ["public char quote = '\"'; public int value = Constants.VALUE; public String text = \"x\";",
                  'public String text = """\n" quoted\n"""; public int value = Constants.VALUE; '
                  'public String suffix = "x";']
        for body in bodies:
            with self.subTest(body=body), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "B.java").write_text("package fixture; public class B { " + body + " }")
                used = MINIMIZER.source_usage([root], {"fixture.Constants": {"library"}}, {"library"})
                self.assertEqual(used, {"library"})

    def test_tracks_inlined_constants_in_generated_and_declared_test_sources(self):
        for folder in ("target/generated-test-sources/fixture", "custom-tests/fixture"):
            with self.subTest(folder=folder), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                command = self.external_fixture(root)
                constants = root / "Constants.java"
                constants.write_text("package fixture; public class Constants { "
                                     "public static final int VALUE = 7; }")
                source = root / "b" / folder / "BTest.java"
                source.parent.mkdir(parents=True)
                source.write_text('package fixture; public class BTest { public String text = """\n'
                                  '" quoted\n"""; public int value = Constants.VALUE; '
                                  'public String suffix = "x"; }')
                subprocess.run(["javac", "-d", str(root / "b/target/test-classes"),
                                str(constants), str(source)],
                               check=True, capture_output=True)
                with zipfile.ZipFile(root / "c-1.jar", "a") as archive:
                    archive.write(root / "b/target/test-classes/fixture/Constants.class",
                                  "fixture/Constants.class")
                # Constants belongs to the library, never to B's test output.
                (root / "b/target/test-classes/fixture/Constants.class").unlink()
                if folder.startswith("custom"):
                    pom = root / "b/pom.xml"
                    pom.write_text(pom.read_text().replace("</project>",
                        "<build><testSourceDirectory>custom-tests</testSourceDirectory></build>\n</project>"))
                self.write_effective_models(root)
                result = subprocess.run(command + ["--apply"], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                dependency = MINIMIZER.AUDIT.ET.parse(root / "b/pom.xml").getroot().find(
                    "m:dependencies/m:dependency", MINIMIZER.AUDIT.NS)
                self.assertEqual(dependency.findtext("m:scope", namespaces=MINIMIZER.AUDIT.NS), "test")

    def test_preserves_optional_and_inherited_exclusion_contracts(self):
        for optional in (True, False):
            with self.subTest(optional=optional), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                constraint = "<optional>true</optional>" if optional else ""
                command = self.external_fixture(root, constraint=constraint)
                if not optional:
                    consumer = root / "a/pom.xml"
                    consumer.write_text(consumer.read_text().replace("    <dependencies>",
                        "    <parent><groupId>example</groupId><artifactId>root</artifactId>"
                        "<version>1</version></parent>\n    <dependencies>"))
                    parent = root / "pom.xml"
                    parent.write_text(parent.read_text().replace("    <dependencies>\n",
                        "    <dependencies>\n        <dependency><groupId>example</groupId>"
                        "<artifactId>b</artifactId><exclusions><exclusion><groupId>library</groupId>"
                        "<artifactId>c</artifactId></exclusion></exclusions></dependency>\n"))
                    exclusion = MINIMIZER.AUDIT.ET.parse(parent).getroot().find(
                        "m:dependencies/m:dependency/m:exclusions", MINIMIZER.AUDIT.NS)
                    self.write_effective_models(root, {"a": lambda model: model.find(
                        "m:dependencies/m:dependency", MINIMIZER.AUDIT.NS).append(exclusion)})
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Dependency relocations: 0", result.stderr)

    def test_managed_exclusions_preserve_the_declaration_when_the_excluded_artifact_is_absent(self):
        for inherited in (False, True):
            with self.subTest(inherited=inherited), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                command = self.external_fixture(root)
                donor = root / "b/pom.xml"
                management = ('<dependencyManagement><dependencies><dependency><groupId>library</groupId>'
                              '<artifactId>c</artifactId><version>1</version><exclusions><exclusion>'
                              '<groupId>library</groupId><artifactId>d</artifactId></exclusion></exclusions>'
                              '</dependency></dependencies></dependencyManagement>')
                manager = root / "pom.xml" if inherited else donor
                manager.write_text(manager.read_text().replace("</project>", management + "\n</project>"))
                if inherited:
                    donor.write_text(donor.read_text().replace("    <dependencies>",
                        '<parent><groupId>example</groupId><artifactId>root</artifactId>'
                        '<version>1</version></parent>\n    <dependencies>'))
                before = donor.read_text()
                exclusion = MINIMIZER.AUDIT.ET.parse(manager).getroot().find(
                    "m:dependencyManagement/m:dependencies/m:dependency/m:exclusions", MINIMIZER.AUDIT.NS)
                self.write_effective_models(root, {"b": lambda model: model.find(
                    "m:dependencies/m:dependency", MINIMIZER.AUDIT.NS).append(exclusion)})
                result = subprocess.run(command + ["--apply"], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Dependency relocations: 0", result.stderr)
                self.assertEqual(donor.read_text(), before)

    def test_inherits_a_custom_test_root_using_the_child_basedir_for_source_annotations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            command = self.external_fixture(root)
            parent = root / "pom.xml"
            parent.write_text(parent.read_text().replace("</project>",
                '<build><testSourceDirectory>${project.basedir}/custom-tests</testSourceDirectory>'
                '</build>\n</project>'))
            donor = root / "b/pom.xml"
            donor.write_text(donor.read_text().replace("    <dependencies>",
                '<parent><groupId>example</groupId><artifactId>root</artifactId>'
                '<version>1</version></parent>\n    <dependencies>'))
            annotation = root / "Marker.java"
            annotation.write_text("package fixture; @java.lang.annotation.Retention("
                                  "java.lang.annotation.RetentionPolicy.SOURCE) public @interface Marker {}")
            source = root / "b/custom-tests/fixture/BTest.java"
            source.parent.mkdir(parents=True)
            source.write_text("package fixture; @Marker public class BTest {}")
            subprocess.run(["javac", "-d", str(root / "test-compiled"), str(annotation), str(source)],
                           check=True, capture_output=True)
            target = root / "b/target/test-classes/fixture/BTest.class"
            target.parent.mkdir(parents=True)
            shutil.copy(root / "test-compiled/fixture/BTest.class", target)
            with zipfile.ZipFile(root / "c-1.jar", "a") as archive:
                archive.write(root / "test-compiled/fixture/Marker.class", "fixture/Marker.class")
            def inherited_root(model):
                namespace = "{" + MINIMIZER.AUDIT.NS["m"] + "}"
                build = MINIMIZER.AUDIT.ET.SubElement(model, namespace + "build")
                source_root = MINIMIZER.AUDIT.ET.SubElement(build, namespace + "testSourceDirectory")
                source_root.text = str(root / "b/custom-tests")

            self.write_effective_models(root, {"b": inherited_root})
            result = subprocess.run(command + ["--apply"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            dependency = MINIMIZER.AUDIT.ET.parse(donor).getroot().find(
                "m:dependencies/m:dependency", MINIMIZER.AUDIT.NS)
            self.assertIsNotNone(dependency)
            self.assertEqual(dependency.findtext("m:scope", namespaces=MINIMIZER.AUDIT.NS), "test")
            subprocess.run(["javac", "-cp", str(root / "c-1.jar"), "-d", str(root / "after-compiled"),
                            str(source)], check=True, capture_output=True)

    def test_rejects_a_concurrent_pom_edit_during_bytecode_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.external_fixture(root)
            path = root / "b/pom.xml"
            original_usage = MINIMIZER.module_usage
            edited = False

            def analyze(*args):
                nonlocal edited
                if not edited:
                    edited = True
                    path.write_text(path.read_text().replace("</project>", "<!-- concurrent -->\n</project>"))
                return original_usage(*args)

            original_consumer = (root / "a/pom.xml").read_text()
            with (patch.object(MINIMIZER, "module_usage", side_effect=analyze),
                  contextlib.redirect_stdout(io.StringIO())):
                with self.assertRaisesRegex(ValueError, "Concurrent change"):
                    MINIMIZER.run(root, True)
            self.assertEqual((root / "a/pom.xml").read_text(), original_consumer)
            self.assertIn("<!-- concurrent -->", path.read_text())


if __name__ == "__main__":
    unittest.main()
