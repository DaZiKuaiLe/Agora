#!/usr/bin/env python3
"""Parse Agora context-detail export JSON for human reading.

Writes a sibling directory named after the export file (without .json).
Current outputs: compact JSON, plus digest.md from the compact payload.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def load_export(path: Path) -> list[Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError(f"expected a JSON array, got {type(data).__name__}")
    return data


def compact_content(items: list[Any]) -> Any:
    compact_items = [
        item for item in items
        if isinstance(item, dict) and item.get("role") == "compact"
    ]
    if not compact_items:
        raise ValueError("no item with role 'compact'")
    if len(compact_items) > 1:
        print(
            f"warning: {len(compact_items)} compact items; using the first",
            file=sys.stderr,
        )
    content = compact_items[0].get("content")
    if isinstance(content, (dict, list)):
        return content
    if not isinstance(content, str):
        raise ValueError(f"compact content is {type(content).__name__}, expected JSON text")
    return json.loads(content)


def digest_markdown(payload: Any) -> str:
    if not isinstance(payload, dict):
        raise ValueError("compact content is not an object")
    digest = payload.get("digest")
    if not isinstance(digest, str):
        raise ValueError("compact JSON has no string 'digest'")
    return digest if digest.endswith("\n") else digest + "\n"


def output_directory(source: Path, override: Path | None) -> Path:
    if override is not None:
        return override.expanduser().resolve()
    return source.parent / source.stem


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extract compact content from an Agora context export.",
    )
    parser.add_argument("input", type=Path, help="agora_context_*.json export")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        help="output directory (default: sibling folder named after the input file)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    source = args.input.expanduser().resolve()
    if not source.is_file():
        print(f"error: file not found: {source}", file=sys.stderr)
        return 1
    try:
        payload = compact_content(load_export(source))
        digest = digest_markdown(payload)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    dest_dir = output_directory(source, args.output)
    dest_dir.mkdir(parents=True, exist_ok=True)
    compact_path = dest_dir / f"{source.stem}_compact.json"
    digest_path = dest_dir / "digest.md"
    compact_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    digest_path.write_text(digest, encoding="utf-8")
    print(compact_path)
    print(digest_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
