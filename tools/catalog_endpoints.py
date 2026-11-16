#!/usr/bin/env python3
"""Extract Retrofit request annotations with method, parameter, and source evidence."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("analysis_root", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    roots = [args.analysis_root / "jadx-primary" / "sources"]
    roots += sorted(args.analysis_root.glob("jadx-dex*/sources"))
    methods = {"x740": "GET", "ngr0": "POST", "pgr0": "PUT", "xzo": "DELETE",
               "rfr0": "PATCH", "o260": "HEAD", "qwp0": "OPTIONS"}
    # Mapping checked against p.we70's annotation dispatch in classes2.dex.
    java_files = []
    pattern = r"@p\.(" + "|".join(methods) + r")\("
    for root in roots:
        result = subprocess.run(["rg", "-l", pattern, str(root), "--glob", "*.java"], capture_output=True, text=True)
        if result.returncode not in (0, 1):
            raise RuntimeError(result.stderr)
        java_files += [(root, Path(name)) for name in result.stdout.splitlines()]
    rows = []
    seen = set()
    for root, path in java_files:
            content = path.read_text(errors="replace")
            if not any(f"@p.{name}(" in content for name in methods):
                continue
            lines = content.splitlines()
            for i, line in enumerate(lines):
                match = re.search(r'@p\.([A-Za-z0-9_]+)\("([^"\n]*)"\)', line)
                if not match or match.group(1) not in methods:
                    continue
                route = match.group(2)
                if not route:
                    continue
                start = i
                while start > 0 and lines[start-1].lstrip().startswith("@"):
                    start -= 1
                end = i+1
                while end < len(lines) and end < i+12:
                    if ";" in lines[end] or "{" in lines[end] and not lines[end].lstrip().startswith("@"):
                        break
                    end += 1
                block = "\n".join(lines[start:min(end+1,len(lines))])
                key = (str(path.relative_to(root)), methods[match.group(1)], route, block)
                if key in seen:
                    continue
                seen.add(key)
                rows.append({"method": methods[match.group(1)], "route": route,
                             "class": str(path.relative_to(root)).replace("/", ".").removesuffix(".java"),
                             "source": str(path), "line": i+1,
                             "declaration": block, "sha256": hashlib.sha256(content.encode()).hexdigest()})
    args.output.mkdir(parents=True, exist_ok=True)
    rows.sort(key=lambda r: (r["route"], r["method"], r["class"]))
    (args.output / "endpoints.json").write_text(json.dumps({"annotationMethods": methods, "endpoints": rows}, indent=2)+"\n")
    table = ["# Extracted Android endpoint catalog", "",
             "These routes come from request annotations in the supplied Spotify build. Relative routes require their configured service base URL. An annotation does not prove the server currently accepts the request. Full declarations and source references are in `endpoints.json`.", "",
             "| Method | Route | Source class |", "| --- | --- | --- |"]
    table += [f'| {r["method"]} | `{r["route"].replace("|", "&#124;")}` | `{r["class"]}` |' for r in rows]
    (args.output / "endpoints.md").write_text("\n".join(table)+"\n")
    print(json.dumps({"annotations": methods, "endpoints": len(rows)}, indent=2))


if __name__ == "__main__":
    main()
