#!/usr/bin/env python3
"""Write ELF metadata, symbols, disassembly, and string offsets for every library."""
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("library_dir", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    disassembler = shutil.which("llvm-objdump") or shutil.which("objdump")
    rows = []
    for path in sorted(args.library_dir.glob("*.so")):
        data = path.read_bytes()
        commands = {"elf": ["readelf", "-h", "-l", "-d", "-n", "-W", str(path)],
                    "symbols": ["readelf", "-Ws", str(path)],
                    "strings": ["strings", "-a", "-t", "x", str(path)],
                    "disassembly": [disassembler, "-d", str(path)]}
        results = {}
        for label, command in commands.items():
            with (args.output / f"{path.name}.{label}.txt").open("w") as out:
                result = subprocess.run(command, stdout=out, stderr=subprocess.PIPE, text=True)
            results[label] = {"exitCode": result.returncode, "stderr": result.stderr[:4000]}
        rows.append({"library": path.name, "bytes": len(data),
                     "sha256": hashlib.sha256(data).hexdigest(), "tools": results})
    (args.output / "native-inventory.json").write_text(json.dumps(rows, indent=2) + "\n")
    print(json.dumps(rows, indent=2))


if __name__ == "__main__":
    main()
