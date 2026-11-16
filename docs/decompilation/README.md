# Decompilation output and reproduction

## Completed output

The analysis recovered all 108,190 DEX class definitions as smali. Each of the 15 per-DEX ZIP files has a count matching its original DEX class table. [Coverage evidence](../evidence/decompilation-coverage.json) records those comparisons. All 15 DEX files completed JADX fallback output. JADX can inline or merge synthetic classes, so Java-file counts are not equivalent to original class counts.

The base manifest, resources and assets were decoded with Apktool. English and xxhdpi split resources were decoded separately. Resource references that belong to another split can remain unresolved in a separately decoded APK; the original split files remain available for resolution. All twelve ARM64 native libraries have ELF metadata, dynamic symbols, strings with file offsets, and full instruction disassembly. Native C/C++ source was not recovered.

The Orbit scanner recovered 881 serialized `FileDescriptorProto` records. The repository retains readable `.proto` renderings and raw descriptor bytes, with binary offsets in the index. The readable renderings preserve message/field structure for analysis; they omit some descriptor options, reserved declarations, and other compiler details. Use the raw descriptor as the canonical recovered artifact. Compact/minimal protobuf metadata, including Login5 v3/v4 types, is outside the scanner's supported format.

Selected methods also receive normal JADX output under `analysis/spotify/java-focused/`. Normal decompilation can fail to structure control flow, invent symbolic constant aliases, or show fallback blocks. Smali is the reference when the normal rendering is ambiguous. No output should be treated as the original buildable source tree.

## Local directories

| Path | Contents |
| --- | --- |
| `analysis/input/spotify.apks` | Downloaded four-APK container |
| `analysis/input/spotify/` | Original APKs |
| `analysis/spotify/dex/` | Original 15 DEX files |
| `analysis/spotify/jadx-primary/`, `jadx-dex2/` through `jadx-dex15/` | Completed Java fallback output |
| `analysis/spotify/java-focused/` | Normal decompilation of reviewed classes |
| `analysis/spotify/smali-zips/` | Full smali, one ZIP per DEX |
| `analysis/spotify/apktool-resources/` | Base resources, manifest, assets |
| `analysis/spotify/split-en/`, `split-xxhdpi/` | Split resources |
| `analysis/spotify/native/` | Original twelve native libraries |
| `analysis/spotify/native-reports/` | ELF/symbol/string/disassembly output |
| `analysis/spotify/native-protos/` | Recovered schemas, JSON and binary descriptors |
| `analysis/spotify/inventory/` | DEX classes and indexed strings |
| `analysis/logs/` | Tool logs, including failed and superseded attempts |
| `artifacts/` | ZIP packages of complete local output |

The initial whole-app JADX and flat-file Apktool attempts are partial and are not used for completeness claims. Per-DEX fallback output and final per-DEX smali ZIPs are the completed outputs. A packaged Smali API wrapper avoids the optimized Apktool jar's removed CLI main method and writes directly to ZIP, reducing filesystem overhead.

## Packaged output

The local repository contains four completed archives. [The artifact manifest](../evidence/local-artifacts.json) records their sizes, SHA-256 hashes, entry counts, and CRC verification. The archives are ignored by Git and remain available under `/root/klang-spotify/artifacts/`.

| Archive | Contents |
| --- | --- |
| `spotify-java.zip` | All fifteen per-DEX Java fallback outputs and focused normal renderings |
| `spotify-smali.zip` | Fifteen nested ZIPs containing all 108,190 smali class definitions |
| `spotify-native-analysis.zip` | Full native instruction disassembly, ELF/symbol/string reports, recovered schemas and descriptors |
| `spotify-resources.zip` | Decoded base, English, and xxhdpi resources, manifest and assets |

The packaged Java output has 103,987 files. Its count differs from the DEX class count because JADX can merge or inline synthetic definitions. The smali class count is the completeness check.

## Tools

The pass used JADX 1.5.6, Apktool 3.0.3 for resource decoding, the retained Baksmali API in Apktool 2.12.1 for smali, Android SDK `aapt`/`apksigner`, `readelf`, LLVM `llvm-objdump`, `strings`, Java, and Python. Tool download hashes are in the coverage evidence. The protobuf scanner additionally needs Python `google.protobuf`; the session used protobuf 7.36.2 from a downloaded wheel in an isolated local directory.

## Reproduction

Use a separate working directory. Copy the APK set to `analysis/input/spotify.apks`, validate the container hash against the recorded artifact, extract the four APKs, and verify each signature before using its contents. Extract DEX files from the base and libraries from the ARM64 split.

```sh
python3 tools/dex_inventory.py analysis/spotify/dex analysis/spotify/inventory
java -Xmx512m -jar analysis/tools/apktool.jar d -f -j 1 --no-src \
  analysis/input/spotify/base.apk -o analysis/spotify/apktool-resources
```

Run JADX independently for each DEX. Adjust heap and worker count to the machine; this session used two workers and a 768 MB heap for per-DEX work.

```sh
java -Xmx768m -cp analysis/tools/jadx/lib/jadx-1.5.6-all.jar jadx.cli.JadxCLI \
  --decompilation-mode fallback --no-res -j 2 \
  -d analysis/spotify/jadx-primary analysis/spotify/dex/classes.dex
```

Compile the smali API wrapper and invoke it for each DEX, passing a ZIP output path. The wrapper uses the APIs retained in the named Apktool jar and may need adaptation for another release.

```sh
javac -cp analysis/tools/apktool-2.jar -d analysis/tools/classes tools/SmaliDump.java
java -Xmx384m -cp analysis/tools/classes:analysis/tools/apktool-2.jar SmaliDump \
  analysis/spotify/dex/classes.dex analysis/spotify/smali-zips/classes.zip
python3 tools/native_inventory.py analysis/spotify/native analysis/spotify/native-reports
PYTHONPATH=analysis/tools/python python3 tools/extract_native_protos.py \
  analysis/spotify/native/liborbit-jni-spotify.so analysis/spotify/native-protos
python3 tools/catalog_endpoints.py analysis/spotify docs/evidence/api
```

Full output is local because it is large and includes third-party copyrighted code. Reports, hashes, extracted contracts and focused evidence remain in the repository. No decompiled source is a licensed Spotify streaming SDK.
