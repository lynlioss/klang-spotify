#!/usr/bin/env python3
"""Validate the independently distributed API 1 source manifest before building."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / "plugin.json").read_text())
assert manifest == json.loads((root / "plugin/src/main/res/raw/plugin.json").read_text()), "Embedded manifest differs"
build = (root / "plugin/build.gradle.kts").read_text()
assert f'versionName = "{manifest["version"]}"' in build
assert manifest["apiVersion"] == 1 and manifest["type"] == "musicSource"
assert manifest["name"] == "Spotify Integration" and manifest["defaultEnabled"] is False
source = manifest["musicSource"]
assert source["package"] in build
assert source["service"].startswith(source["package"] + ".")
assert source["settingsActivity"].startswith(source["package"] + ".")
assert manifest["musicImport"]["package"] == source["package"]
assert manifest["musicImport"]["service"] == source["service"]
assert {"catalog.search", "stream.resolve", "offline.download", "accounts"} <= set(source["capabilities"])
assert "GPL" in (root / "README.md").read_text()
android = (root / "plugin/src/main/AndroidManifest.xml").read_text()
assert "MUSIC_SOURCE_V1" in android and "MUSIC_IMPORT_V1" in android and 'android:allowBackup="false"' in android
print("API 1 manifests, package, version, services and account boundary agree")
