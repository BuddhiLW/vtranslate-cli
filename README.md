# vtranslate-cli

Babashka **driving adapter** for [`vtranslate-engine`](../vtranslate-engine) — the
JVM Clojure subtitle-translation core. This CLI owns **no domain types**; it only
marshals argv → EDN job-spec → engine subprocess → stdout.

```
video → demux audio → ASR (whisper) → machine-translate → render/mux subtitles
```

## Why a subprocess
The engine needs the JVM + native ffmpeg (bytedeco JavaCV) and cannot load into
babashka/SCI. So the CLI **shells out** to it (`clojure -M:ffmpeg:run` in the
engine checkout) rather than depending on it as a classpath library.

## Usage
```
bb run translate <source> <target-lang> [--source-lang en] [--format srt|vtt] [--job-id id]
bb run version
bb run help
```
Install as a standalone command with [bbin](https://github.com/babashka/bbin):
```
bbin install .
vtranslate translate clip.mp4 pt-BR
```

## Engine location
Runs the engine from `../vtranslate-engine` by default. Override with:
```
export VTRANSLATE_ENGINE_DIR=/path/to/vtranslate-engine
```

## Release / versioning
`VERSION` (plain semver) is the source of truth. Pushing to `main` runs
`.github/workflows/release.yml`, which tags `vX.Y.Z` and cuts a GitHub Release
with auto-generated notes. The pinned engine coordinate in `deps.edn` is kept
current by [`bb-depsolve`](https://github.com/hive-agi/bb-depsolve)
(`bb sync` / `bb upgrade` / `bb deps-report` from the `vtranslate/` workspace root).
