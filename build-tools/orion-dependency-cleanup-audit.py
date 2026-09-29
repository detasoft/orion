#!/usr/bin/env python3
"""Report direct Maven dependencies that also have a transitive path."""

import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def artifact_key(node):
    return (node["groupId"], node["artifactId"], node.get("type", "jar"), node.get("classifier", ""))


def module_poms(path):
    pom = ET.parse(path).getroot()
    yield path, pom
    for module in pom.findall("m:modules/m:module", NS):
        yield from module_poms(path.parent / module.text / "pom.xml")


def descendants(node, route):
    for child in node.get("children", []):
        path = route + (child,)
        yield child, path
        yield from descendants(child, path)


def path_scope(route, direct, removed):
    scope = route[0]["scope"]
    for node in route[1:]:
        key = artifact_key(node)
        declaration = direct.get(key, node) if key != removed else node
        if scope == "compile":
            scope = declaration["scope"]
    return scope


def audit(root):
    candidates = 0
    constraints = 0
    for path, pom in module_poms(root / "pom.xml"):
        tree_path = path.parent / "target/dependency-audit.json"
        tree = json.loads(tree_path.read_text(encoding="utf-8"))
        direct = {artifact_key(node): node for node in tree.get("children", [])}
        for dependency in pom.findall("m:dependencies/m:dependency", NS):
            key = tuple(dependency.findtext("m:" + name, default=default, namespaces=NS)
                        for name, default in [("groupId", ""), ("artifactId", ""),
                                              ("type", "jar"), ("classifier", "")])
            declaration = direct.get(key)
            if declaration is None:
                continue
            alternatives = []
            for other_key, other in direct.items():
                if other_key != key:
                    for node, route in descendants(other, (other,)):
                        if artifact_key(node) == key:
                            alternatives.append((node, route, path_scope(route, direct, key)))
            if not alternatives:
                continue
            matching = [(node, route, scope) for node, route, scope in alternatives
                        if node["version"] == declaration["version"] and scope == declaration["scope"]]
            flags = []
            if dependency.find("m:exclusions", NS) is not None:
                flags.append("exclusions")
            if dependency.findtext("m:optional", namespaces=NS) == "true":
                flags.append("optional")
            if matching and not flags:
                candidates += 1
                label = "candidate"
            else:
                constraints += 1
                label = "scope/version constraint" if not flags else ", ".join(flags)
            node, route, scope = min(matching or alternatives, key=lambda item: len(item[1]))
            coordinates = ":".join(key[:2])
            if key[3]:
                coordinates += ":" + key[3]
            print(f"{path.relative_to(root)}: {coordinates} [{label}]")
            print(f"  direct: {declaration['version']}/{declaration['scope']}; "
                  f"via {' -> '.join(node['artifactId'] for node in route)}: {node['version']}/{scope}")
    print(f"Candidates: {candidates}; Scope/version constraints: {constraints}")
    print("Candidates require classpath verification: removal can change mediation of descendant dependencies.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="repository root containing pom.xml")
    args = parser.parse_args()
    try:
        audit(args.root.resolve())
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Dependency audit failed: {error}. Generate trees with make dependency-audit.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
