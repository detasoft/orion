#!/usr/bin/env python3

import re
import sys
from pathlib import Path

import yaml


ALLOWED_FRONTMATTER = {"name", "description", "license", "allowed-tools", "metadata"}
SKILL_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


def validate(skill_directory: str) -> str | None:
    skill_path = Path(skill_directory) / "SKILL.md"
    if not skill_path.is_file():
        return "SKILL.md not found"

    content = skill_path.read_text()
    frontmatter_match = re.match(r"^---\n(.*?)\n---", content, re.DOTALL)
    if frontmatter_match is None:
        return "invalid or missing YAML frontmatter"

    try:
        frontmatter = yaml.safe_load(frontmatter_match.group(1))
    except yaml.YAMLError as error:
        return f"invalid YAML frontmatter: {error}"

    if not isinstance(frontmatter, dict):
        return "frontmatter must be a mapping"

    unexpected = set(frontmatter) - ALLOWED_FRONTMATTER
    if unexpected:
        return f"unexpected frontmatter keys: {', '.join(sorted(unexpected))}"

    name = frontmatter.get("name")
    if not isinstance(name, str) or not SKILL_NAME.fullmatch(name) or len(name) > 64:
        return "name must be at most 64 characters in lowercase hyphen-case"

    description = frontmatter.get("description")
    if not isinstance(description, str) or not description.strip():
        return "description must be a non-empty string"
    if "<" in description or ">" in description:
        return "description cannot contain angle brackets"
    if description.strip().startswith("[TODO:"):
        return "description contains an unfinished TODO"

    return None


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: validate-skill.py <skill-directory>", file=sys.stderr)
        return 2

    error = validate(sys.argv[1])
    if error is not None:
        print(f"{sys.argv[1]}: {error}", file=sys.stderr)
        return 1

    print(f"{sys.argv[1]}: valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
