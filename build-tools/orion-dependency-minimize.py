#!/usr/bin/env python3
"""Move unused Maven dependencies to their actual consumers; print a patch unless --apply is set."""

import argparse
import difflib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "dependency_audit", Path(__file__).with_name("orion-dependency-cleanup-audit.py"))
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def available(graph, module, tests=False, exported=False):
    found = set()
    allowed = {"compile", "runtime"} if tests else {"compile"}
    if not exported:
        allowed.add("provided")
        if tests:
            allowed.add("test")
    queue = [target for target, scope in graph[module].items()
             if scope in allowed]
    while queue:
        target = queue.pop()
        if target in found:
            continue
        if graph[module].get(target, "compile") not in allowed:
            continue
        found.add(target)
        for child, scope in graph[target].items():
            if scope == "compile" or (tests and scope == "runtime"):
                queue.append(child)
    return found


def minimize(graph, main_uses, test_uses, movable, editable=None, protected=None):
    result = {module: dict(dependencies) for module, dependencies in graph.items()}
    if editable is None:
        editable = {(module, target) for module, dependencies in graph.items() for target in dependencies}
    protected = protected or {}
    moves = []
    for donor in graph:
        if donor not in movable:
            continue
        for target in list(result[donor]):
            if (result[donor][target] != "compile" or target not in movable
                    or (donor, target) not in editable or target in main_uses.get(donor, set())):
                continue
            before_main = {module: available(result, module) for module in result}
            before_test = {module: available(result, module, tests=True) for module in result}
            affected = {module for module in main_uses
                        if module == donor or donor in before_test[module]}
            family = {target} | available(result, target, tests=True, exported=True)
            if any(family & protected.get(module, set()) for module in affected):
                continue
            previous = {module: dict(dependencies) for module, dependencies in result.items()}
            del result[donor][target]
            lost = before_main[donor] - available(result, donor)
            if lost & main_uses.get(donor, set()):
                result[donor][target] = "compile"
                continue
            if (before_test[donor] - available(result, donor, tests=True)) & test_uses.get(donor, set()):
                result[donor][target] = "test"
            additions = []
            distances = {donor: 0}
            queue = [donor]
            for dependency in queue:
                for consumer, dependencies in previous.items():
                    if dependency in dependencies and consumer not in distances:
                        distances[consumer] = distances[dependency] + 1
                        queue.append(consumer)
            consumers = sorted(graph.keys() & main_uses.keys(),
                               key=lambda module: (distances.get(module, len(graph)), module))
            blocked = False
            for consumer in consumers:
                for tests, uses, before, scope in (
                    (False, main_uses, before_main, "compile"),
                    (True, test_uses, before_test, "test"),
                ):
                    required = uses.get(consumer, set()) & before[consumer]
                    for dependency in sorted(required, key=lambda name: (-len(available(result, name)), name)):
                        if dependency not in available(result, consumer, tests=tests):
                            if dependency not in movable:
                                blocked = True
                                break
                            result[consumer][dependency] = scope
                            additions.append((consumer, dependency, scope))
            if blocked:
                result = previous
                continue
            moves.append((donor, target, additions))
    return result, moves


def module_usage(directory, owners, classpath):
    if not directory.is_dir() or not any(directory.rglob("*.class")):
        return set(), set()
    command = ["jdeps", "--multi-release", "21", "-verbose:class", "-filter:none", "--ignore-missing-deps",
               "--class-path", classpath, str(directory)]
    output = subprocess.run(command, check=True, capture_output=True, text=True).stdout
    used = set()
    external = set()
    for name, archive in re.findall(r"(?m)^\s+[\w.$]+\s+->\s+([\w.$]+)\s+([^\n]+)", output):
        if archive.strip() == "not found" and not name.startswith("java."):
            raise ValueError(f"Incomplete bytecode classpath in {directory}: {name}")
        if archive.strip().endswith(".jar"):
            external.add(Path(archive.strip()).name)
        elif name in owners:
            used.add(owners[name])
    return used, external


def artifact_name(identity):
    return identity.split(":")[1] if ":" in identity else identity


def archive_name(node):
    classifier = "-" + node["classifier"] if node.get("classifier") else ""
    return node["artifactId"] + "-" + node["version"] + classifier + ".jar"


def source_usage(directories, owners, reachable):
    names = {name.replace("$", "."): targets & reachable for name, targets in owners.items()
             if targets & reachable}
    packages = {}
    for name, targets in names.items():
        package, _, simple = name.rpartition(".")
        packages.setdefault(package, {})[simple] = targets
    used = set()
    for directory in directories:
        for path in directory.rglob("*.java"):
            ignored = (r'//[^\n]*|/\*.*?\*/|"""(?:\\.|(?!""").)*"""|"(?:\\.|[^"\\])*"|'
                       + r"'(?:\\.|[^'\\])*'")
            source = re.sub(ignored, " ", path.read_text(encoding="utf-8"), flags=re.S)
            source = re.sub(r"\s*\.\s*", ".", source)
            package = re.search(r"\bpackage\s+([\w.]+)", source)
            tokens = set(re.findall(r"\b[\w$]+\b", source))
            if package:
                for simple, targets in packages.get(package.group(1), {}).items():
                    if simple in tokens:
                        used.update(targets)
            for imported in re.findall(r"\bimport\s+(?:static\s+)?([\w.$]+(?:\.\*)?)", source):
                if imported.endswith(".*"):
                    prefix = imported[:-2]
                    for targets in packages.get(prefix, {}).values():
                        used.update(targets)
                    used.update(names.get(prefix, set()))
            for reference in re.findall(r"\b(?:[\w$]+\.)+[\w$]+", source):
                while reference:
                    if reference in names:
                        used.update(names[reference])
                        break
                    reference = reference.rpartition(".")[0]
    return used


def render_pom(text, old, new, coordinates):
    changes = {target for target in old.keys() | new.keys() if old.get(target) != new.get(target)}
    if not changes:
        return text
    stack = []
    section = None
    tokens = r"<!--.*?-->|<!\[CDATA\[.*?\]\]>|<(/?)([\w:-]+)(?:\s[^<>]*?)?\s*(/?)>"
    for token in re.finditer(tokens, text, flags=re.S):
        closing, name, empty = token.groups()
        if name is None:
            continue
        if closing:
            stack.pop()
        else:
            if name == "dependencies" and stack == ["project"]:
                start = text.rfind("\n", 0, token.start()) + 1
                section = re.match(r"( *)<dependencies>\s*\n", text[start:])
                if section is None:
                    raise ValueError("Direct dependencies must use a multiline POM section")
                section_end = start + section.end()
                break
            if not empty:
                stack.append(name)
    if section is None:
        indents = re.findall(r"(?m)^( +)<(?:parent|modelVersion|artifactId|groupId|build|profiles)>", text)
        indent = min(indents, key=len) if indents else "    "
        closing = text.index("</project>")
        text = text[:closing] + indent + "<dependencies>\n" + indent + "</dependencies>\n" + text[closing:]
        section = re.match(r"( *)<dependencies>\s*\n", text[closing:])
        section_end = closing + section.end()
    indent = section.group(1)
    end = text.index(indent + "</dependencies>", section_end)
    content = text[section_end:end]
    dependency_indents = re.findall(r"(?m)^( +)<dependency>\s*$", content)
    child_indent = min(dependency_indents, key=len) if dependency_indents else indent + "    "
    field_indent = child_indent + "    "
    found = set()

    def replace(match):
        block = match.group(0)
        artifact = re.search(r"<artifactId>([^<]+)</artifactId>", block).group(1)
        group = re.search(r"<groupId>([^<]+)</groupId>", block).group(1)
        dependency_type = re.search(r"<type>([^<]+)</type>", block)
        classifier = re.search(r"<classifier>([^<]+)</classifier>", block)
        if (dependency_type and dependency_type.group(1) != "jar") or classifier:
            return block
        targets = [target for target in changes
                   if artifact_name(target) == artifact and coordinates[target][0] == group]
        if not targets:
            return block
        if len(targets) != 1:
            raise ValueError("Ambiguous dependency declaration: " + group + ":" + artifact)
        target = targets[0]
        found.add(target)
        if target not in new:
            return ""
        scope = field_indent + "<scope>" + new[target] + "</scope>\n"
        if re.search(r"<scope>[^<]+</scope>", block):
            return re.sub(r"(?m)^ +<scope>[^<]+</scope>\n", scope, block)
        return block.replace(child_indent + "</dependency>", scope + child_indent + "</dependency>")

    pattern = (r"(?m)^" + re.escape(child_indent) + r"<dependency>\n.*?^"
               + re.escape(child_indent) + r"</dependency>\n")
    content = re.sub(pattern, replace, content, flags=re.S)
    if (changes & old.keys()) - found - new.keys():
        raise ValueError("Cannot locate the dependency declaration to move")
    for artifact in sorted(changes - found):
        group, version = coordinates[artifact]
        content += (child_indent + "<dependency>\n" + field_indent + "<groupId>" + group + "</groupId>\n"
                    + field_indent + "<artifactId>" + artifact_name(artifact) + "</artifactId>\n"
                    + field_indent + "<version>" + version + "</version>\n")
        if new[artifact] != "compile":
            content += field_indent + "<scope>" + new[artifact] + "</scope>\n"
        content += child_indent + "</dependency>\n"
    return text[:section_end] + content + text[end:]


def run(root, apply):
    modules = {}
    coordinates = {}
    originals = {}
    model_path = root / "target/dependency-effective-pom.xml"
    document = AUDIT.ET.parse(model_path).getroot()
    models = [document] if document.tag.endswith("}project") else document.findall("m:project", AUDIT.NS)
    effective = {(model.findtext("m:groupId", namespaces=AUDIT.NS),
                  model.findtext("m:artifactId", namespaces=AUDIT.NS)): model for model in models}
    for path, pom in AUDIT.module_poms(root / "pom.xml"):
        tree = json.loads((path.parent / "target/dependency-audit.json").read_text(encoding="utf-8"))
        artifact = tree["artifactId"]
        if artifact in modules:
            raise ValueError("Ambiguous reactor artifactId: " + artifact)
        if path.stat().st_mtime_ns > model_path.stat().st_mtime_ns:
            raise ValueError("Stale Maven effective model for " + str(path))
        if (tree["groupId"], artifact) not in effective:
            raise ValueError("Missing Maven effective model for " + str(path))
        originals[path] = path.read_text(encoding="utf-8")
        pom = AUDIT.ET.fromstring(originals[path])
        modules[artifact] = (path, pom, tree)
        version = pom.findtext("m:version", namespaces=AUDIT.NS)
        if version is None:
            version = pom.findtext("m:parent/m:version", namespaces=AUDIT.NS)
        dependency_version = "${revision}" if version == "${revision}" else tree["version"]
        coordinates[artifact] = (tree["groupId"], dependency_version)
    owners = {}
    movable = set()
    for module, (path, _, _) in modules.items():
        classes = list((path.parent / "target/classes").rglob("*.class"))
        if classes:
            movable.add(module)
        for file in classes:
            name = ".".join(file.relative_to(path.parent / "target/classes").with_suffix("").parts)
            if name in owners and owners[name] != module:
                raise ValueError("Ambiguous class ownership: " + name)
            owners[name] = module
    graph = {}
    editable = set()
    main_uses = {}
    test_uses = {}
    archives = {}
    versions = {}
    archive_paths = {}
    classpaths = {}
    protected = {}
    exports = {}

    def identity(node):
        artifact = node["artifactId"]
        if (artifact in modules and node["groupId"] == coordinates[artifact][0]
                and node["version"] == modules[artifact][2]["version"]
                and node.get("type", "jar") == "jar" and not node.get("classifier")):
            archives.setdefault(archive_name(node), set()).add(artifact)
            return artifact
        key = ":".join(AUDIT.artifact_key(node))
        coordinates[key] = (node["groupId"], node["version"])
        versions.setdefault(key, set()).add(node["version"])
        archives.setdefault(archive_name(node), set()).add(key)
        graph.setdefault(key, {})
        return key

    for _, _, tree in modules.values():
        for node, _ in AUDIT.descendants(tree, ()):
            identity(node)
    for _, _, tree in modules.values():
        for node, _ in AUDIT.descendants(tree, ()):
            key = identity(node)
            if key in modules or node["scope"] != "compile":
                continue
            children = {}
            for child, route in AUDIT.descendants(node, (node,)):
                if child.get("optional") == "true":
                    continue
                scope = AUDIT.path_scope(route, {}, None)
                if scope in ("compile", "runtime"):
                    child_key = identity(child)
                    if child_key != key:
                        children[child_key] = scope
                        graph[key][child_key] = scope
            if "children" in node:
                exports.setdefault(key, set()).add(tuple(sorted(children.items())))
    for module, (path, _, tree) in modules.items():
        graph[module] = {identity(node): node["scope"] for node in tree.get("children", [])}
        classpath_file = path.parent / "target/dependency-classpath.txt"
        classpath = classpath_file.read_text(encoding="utf-8").strip() if classpath_file.is_file() else ""
        if not classpath and tree.get("children") and module in movable:
            raise ValueError("Missing dependency classpath in " + str(path.parent))
        classpaths[module] = str(path.parent / "target/classes") + os.pathsep + classpath
        for file in classpath.split(os.pathsep):
            if file.endswith(".jar"):
                archive_paths[Path(file).name] = Path(file)
    source_owners = {name: {module} for name, module in owners.items()}
    services = set()
    for archive, targets in archives.items():
        if archive not in archive_paths:
            continue
        with zipfile.ZipFile(archive_paths[archive]) as jar:
            classes = []
            for name in jar.namelist():
                if name.startswith("META-INF/versions/"):
                    pieces = name.split("/", 3)
                    if len(pieces) != 4 or not pieces[2].isdigit() or int(pieces[2]) > 21:
                        continue
                    name = pieces[3]
                if name.endswith(".class") and not name.endswith("module-info.class"):
                    classes.append(name[:-6].replace("/", "."))
            for name in classes:
                source_owners.setdefault(name, set()).update(targets)
            if any(name.startswith("META-INF/services/") and not name.endswith("/") for name in jar.namelist()):
                services.update(targets)
                continue
            if len(targets) != 1 or not classes:
                continue
            target = next(iter(targets))
            if target not in modules and len(versions[target]) == 1 and target.split(":")[2:] == ["jar", ""]:
                movable.add(target)
    for module, (path, pom, tree) in modules.items():
        direct = {AUDIT.artifact_key(node): node for node in tree.get("children", [])}
        model = effective[(tree["groupId"], module)]
        effective_dependencies = {}
        protected[module] = set()
        for dependency in model.findall("m:dependencies/m:dependency", AUDIT.NS):
            key = tuple(dependency.findtext("m:" + name, default=default, namespaces=AUDIT.NS)
                        for name, default in [("groupId", ""), ("artifactId", ""),
                                              ("type", "jar"), ("classifier", "")])
            effective_dependencies[key] = dependency
            if key in direct and (dependency.find("m:exclusions", AUDIT.NS) is not None
                                  or dependency.findtext("m:optional", namespaces=AUDIT.NS) == "true"):
                protected[module].add(identity(direct[key]))
            for exclusion in dependency.findall("m:exclusions/m:exclusion", AUDIT.NS):
                group = exclusion.findtext("m:groupId", namespaces=AUDIT.NS)
                artifact = exclusion.findtext("m:artifactId", namespaces=AUDIT.NS)
                protected[module].update(key for key in graph
                    if (group == "*" or coordinates[key][0] == group)
                    and (artifact == "*" or artifact_name(key) == artifact))
        for dependency in model.findall("m:dependencyManagement/m:dependencies/m:dependency", AUDIT.NS):
            if (dependency.find("m:exclusions", AUDIT.NS) is None
                    and dependency.findtext("m:optional", namespaces=AUDIT.NS) != "true"):
                continue
            group = dependency.findtext("m:groupId", namespaces=AUDIT.NS)
            artifact = dependency.findtext("m:artifactId", namespaces=AUDIT.NS)
            protected[module].update(key for key in graph
                                     if coordinates[key][0] == group and artifact_name(key) == artifact)
        for node, _ in AUDIT.descendants(tree, ()):
            key = identity(node)
            if node.get("optional") == "true":
                protected[module].add(key)
                protected[module].update(identity(child) for child, _ in AUDIT.descendants(node, ()))
            if len(versions.get(key, ())) > 1 or len(exports.get(key, ())) > 1:
                protected[module].add(key)
        for node in tree.get("children", []):
            if node["scope"] in ("provided", "runtime"):
                key = identity(node)
                protected[module].add(key)
                protected[module].update(available(graph, key, tests=True, exported=True))
        for dependency in pom.findall("m:dependencies/m:dependency", AUDIT.NS):
            key = tuple(dependency.findtext("m:" + name, default=default, namespaces=AUDIT.NS)
                        for name, default in [("groupId", ""), ("artifactId", ""),
                                              ("type", "jar"), ("classifier", "")])
            resolved = effective_dependencies.get(key)
            if (key in direct and resolved is not None and key[2:] == ("jar", "")
                    and resolved.find("m:exclusions", AUDIT.NS) is None
                    and resolved.findtext("m:optional", namespaces=AUDIT.NS) != "true"):
                editable.add((module, identity(direct[key])))
        main_uses[module], external_main = module_usage(
            path.parent / "target/classes", owners, classpaths[module])
        test_uses[module], external_test = module_usage(
            path.parent / "target/test-classes", owners, classpaths[module])
        for usage, names in ((main_uses[module], external_main), (test_uses[module], external_test)):
            for archive in names:
                if archive not in archives:
                    raise ValueError("Cannot map bytecode dependency to Maven coordinates: " + archive)
                usage.update(archives[archive])
        reachable = available(graph, module, tests=True) | {module}
        protected[module].update(services & reachable)
        main_sources = [path.parent / "src/main", path.parent / "target/generated-sources"]
        test_sources = [path.parent / "src/test", path.parent / "target/generated-test-sources"]
        for name, directories in (("sourceDirectory", main_sources), ("testSourceDirectory", test_sources)):
            source = model.findtext("m:build/m:" + name, namespaces=AUDIT.NS)
            if source:
                source = (source.replace("${project.basedir}", str(path.parent))
                          .replace("${basedir}", str(path.parent)))
                if "${" in source:
                    protected[module].update(reachable)
                else:
                    directories.append(path.parent / source)
        main_uses[module].update(source_usage(main_sources, source_owners, reachable))
        test_uses[module].update(source_usage(test_sources, source_owners, reachable))
        main_uses[module].discard(module)
        test_uses[module].discard(module)
    minimized, moves = minimize(graph, main_uses, test_uses, movable, editable, protected)
    changes = []
    for module, (path, _, _) in modules.items():
        before = originals[path]
        if path.read_text(encoding="utf-8") != before:
            raise ValueError("Concurrent change in " + str(path))
        after = render_pom(before, graph[module], minimized[module], coordinates)
        if before != after:
            changes.append((path, before, after))
    for donor, target, additions in moves:
        print(f"Remove compile {donor} -> {target}; add {additions}", file=sys.stderr)
    for path, before, after in changes:
        relative = path.relative_to(root)
        patch = difflib.unified_diff(before.splitlines(keepends=True), after.splitlines(keepends=True),
                                    fromfile="a/" + str(relative), tofile="b/" + str(relative))
        sys.stdout.writelines(patch)
    if apply:
        for path, before in originals.items():
            if path.read_text(encoding="utf-8") != before:
                raise ValueError("Concurrent change in " + str(path))
        for path, _, after in changes:
            path.write_text(after, encoding="utf-8")
    print(f"Dependency relocations: {len(moves)}; changed POM files: {len(changes)}", file=sys.stderr)
    constrained = sorted(module for module, targets in protected.items() if targets)
    if constrained:
        print("Constrained or ambiguous dependency paths preserved in: " + ", ".join(constrained),
              file=sys.stderr)
    print("Bytecode and source references do not cover arbitrary reflection or runtime resources; "
          "review the patch and run make test.",
          file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apply", action="store_true", help="write the proposed dependency moves")
    args = parser.parse_args()
    try:
        run(args.root.resolve(), args.apply)
    except (OSError, ValueError, subprocess.CalledProcessError, AUDIT.ET.ParseError) as error:
        message = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            message = (error.stderr or error.stdout or message).strip()
        print(f"Dependency minimization failed: {message}. Run make dependency-minimize.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
