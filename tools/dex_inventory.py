#!/usr/bin/env python3
"""Inventory standard DEX tables without executing app code or importing packages."""
import argparse
import json
import re
import struct
from pathlib import Path


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ValueError("Invalid ULEB128")


def inventory(path):
    data = path.read_bytes()
    if not data.startswith(b"dex\n"):
        raise ValueError(f"Unsupported DEX: {path}")
    def integer(offset):
        return struct.unpack_from("<I", data, offset)[0]
    strings = []
    for i in range(integer(56)):
        offset = integer(integer(60) + 4 * i)
        _, start = uleb(data, offset)
        end = data.index(0, start)
        strings.append(data[start:end].decode("utf-8", errors="replace"))
    types = [strings[integer(integer(68) + 4 * i)] for i in range(integer(64))]
    classes = []
    for i in range(integer(96)):
        offset = integer(100) + 32 * i
        source_id = integer(offset + 16)
        classes.append({"descriptor": types[integer(offset)],
                        "source": strings[source_id] if source_id != 0xFFFFFFFF else None})
    return {"dex": path.name, "version": data[4:7].decode(), "bytes": len(data),
            "stringCount": len(strings), "typeCount": len(types),
            "methodReferences": integer(88), "classCount": len(classes),
            "classes": classes}, strings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dex_dir", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    rows, all_strings = [], []
    for path in sorted(args.dex_dir.glob("*.dex")):
        row, strings = inventory(path)
        rows.append(row)
        all_strings.extend({"dex": path.name, "index": i, "value": value}
                           for i, value in enumerate(strings))
    (args.output / "dex-inventory.json").write_text(json.dumps(rows, indent=2) + "\n")
    with (args.output / "dex-strings.jsonl").open("w") as output:
        for row in all_strings:
            output.write(json.dumps(row) + "\n")
    terms = {
        "auth": r"login5|login/v|oauth|authenticat|token|credential|session|challenge|pkce",
        "streaming": r"storage-resolve|audio-key|audio_key|widevine|playplay|cdn|bitrate|codec|audiofile|storage_resolve|media.?drm|license|streaming",
        "country": r"country|geolocation|location|market|geoip|vpn|roaming",
        "defenses": r"integrity|attest|rootcheck|certificatepinn|safetynet|signature|anti.?tamper|recaptcha|captcha|fraud",
        "api": r"https?://|wss?://|pathfinder|spclient|searchview|search/v|searchquery|graphql|protobuf",
    }
    for key, pattern in terms.items():
        regex = re.compile(pattern, re.I)
        selected = [row for row in all_strings if regex.search(row["value"])]
        (args.output / f"strings-{key}.jsonl").write_text("".join(json.dumps(row) + "\n" for row in selected))
    print(json.dumps({"dexFiles": len(rows), "classes": sum(r["classCount"] for r in rows),
                      "strings": len(all_strings)}, indent=2))


if __name__ == "__main__":
    main()
