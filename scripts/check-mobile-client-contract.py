#!/usr/bin/env python3
"""Bidirectionally compare native method/path inventories with mobile OpenAPI."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts" / "mobile-api.yaml"
CATALOGS = {
    "ANDROID": ROOT / "android-app" / "app" / "src" / "main" / "java" / "com" / "example" / "yingge" / "data" / "contract" / "EndpointCatalog.kt",
    "IOS": ROOT / "ios-app" / "Sources" / "YinggeCore" / "EndpointCatalog.swift",
}
METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}
BACKEND_SOURCE = ROOT / "backend" / "src" / "main" / "java"


def read_contract() -> tuple[dict[str, set[str]], set[str]]:
    text = CONTRACT.read_text(encoding="utf-8")
    expected = {platform: set() for platform in CATALOGS}
    for platforms, method, path in re.findall(
        r'^\s*-\s+"([A-Z,]+)\|(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\|(/[^" ]+)"\s*$',
        text,
        re.MULTILINE,
    ):
        operation = f"{method}|{path}"
        for platform in platforms.split(","):
            if platform not in expected:
                raise SystemExit(f"unknown native platform in contract: {platform}")
            expected[platform].add(operation)
    if not all(expected.values()):
        raise SystemExit("x-native-client-operations missing or empty")

    openapi_operations: set[str] = set()
    in_paths = False
    current_path: str | None = None
    for line in text.splitlines():
        if line == "paths:":
            in_paths = True
            continue
        if in_paths and line and not line.startswith(" "):
            break
        path_match = re.match(r"^  (/[^:]+):\s*$", line) if in_paths else None
        if path_match:
            current_path = path_match.group(1)
            continue
        method_match = re.match(r"^    ([a-z]+):(?:\s|$)", line) if current_path else None
        if method_match and method_match.group(1).upper() in METHODS:
            openapi_operations.add(f"{method_match.group(1).upper()}|{current_path}")
    return expected, openapi_operations


def catalog_operations(path: Path) -> set[str]:
    source = path.read_text(encoding="utf-8")
    marker = source.find("contractOperations")
    if marker < 0:
        raise ValueError("contractOperations inventory missing")
    operations = {
        f"{method}|{endpoint}"
        for method, endpoint in re.findall(
            r'"(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\|(/[^" ]+)"', source[marker:]
        )
    }
    if not operations:
        raise ValueError("contractOperations inventory is empty")
    return operations


def backend_operations() -> set[str]:
    """Collect literal Spring MVC method/path pairs so a hand-written spec cannot invent routes."""
    operations: set[str] = set()
    for path in BACKEND_SOURCE.rglob("*Controller.java"):
        source = path.read_text(encoding="utf-8")
        class_match = re.search(
            r'@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)\s*(?:public\s+)?class\s+',
            source,
            re.DOTALL,
        )
        base = class_match.group(1) if class_match else ""
        for verb, endpoint in re.findall(
            r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?',
            source,
            re.DOTALL,
        ):
            full_path = f"{base.rstrip('/')}/{endpoint.lstrip('/')}" if endpoint else (base or "/")
            operations.add(f"{verb.upper()}|{full_path}")
    return operations


def normalize_template(operation: str) -> str:
    method, endpoint = operation.split("|", 1)
    return f"{method}|{re.sub(r'{[^}]+}', '{}', endpoint)}"


def main() -> int:
    expected, openapi_operations = read_contract()
    failed = False
    declared = set().union(*expected.values())
    missing_openapi = sorted(declared - openapi_operations)
    if missing_openapi:
        print(
            "contract: native operations missing OpenAPI declarations: "
            + ", ".join(missing_openapi),
            file=sys.stderr,
        )
        failed = True
    implemented = {normalize_template(operation) for operation in backend_operations()}
    missing_backend = sorted(
        operation for operation in declared if normalize_template(operation) not in implemented
    )
    if missing_backend:
        print(
            "contract: native operations missing Spring controller mappings: "
            + ", ".join(missing_backend),
            file=sys.stderr,
        )
        failed = True

    for platform, path in CATALOGS.items():
        if not path.is_file():
            print(f"{platform}: endpoint catalog missing: {path.relative_to(ROOT)}", file=sys.stderr)
            failed = True
            continue
        try:
            actual = catalog_operations(path)
        except ValueError as error:
            print(f"{platform}: {error}", file=sys.stderr)
            failed = True
            continue
        missing = sorted(expected[platform] - actual)
        extra = sorted(actual - expected[platform])
        if missing:
            print(f"{platform}: missing native operations: {', '.join(missing)}", file=sys.stderr)
            failed = True
        if extra:
            print(f"{platform}: undeclared native operations: {', '.join(extra)}", file=sys.stderr)
            failed = True
        if not missing and not extra:
            print(f"{platform}: {len(actual)} method/path operations match bidirectionally")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
