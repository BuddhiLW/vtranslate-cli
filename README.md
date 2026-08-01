# vtranslate-cli

A [babashka](https://babashka.org) CLI over **vtranslate-engine**. It edits the
config that selects providers, then shells the engine to run a job. The engine
owns all domain logic; this is a thin driving adapter (argv → config/spec → engine).

Two adapters drive that same core: the `vtranslate` command, and a local web
control panel (`bb web`) for when clicking beats retyping a path.

## Install

```bash
bbin install io.github.BuddhiLW/vtranslate-cli
```

Requires [bbin](https://github.com/babashka/bbin); puts a `vtranslate` script on
`~/.babashka/bbin/bin` (add it to `PATH`). The engine itself is a sibling JVM
project — point `VTRANSLATE_ENGINE_DIR` at its checkout (see *Engine bridge*).

## Arguments are positional (no flags)

Following the [Bonzai](https://github.com/rwxrob/bonzai) command-tree style —
subcommand words + positional operands, no `--flag` soup.

```
vtranslate config path                      print the user config path (XDG)
vtranslate config init                      create the config from defaults (0600)
vtranslate config show [raw]                show effective (or raw user) config
vtranslate config get <dotted.key>          e.g. providers.translator
vtranslate config set <dotted.key> <edn>    e.g. providers.translator :deepl
vtranslate provider use <asr|mt> <name>     select a provider (validated, persisted)
vtranslate provider list [asr|mt]           list providers; * = active, secret status
vtranslate run <source> <target-lang> [source-lang] [format]
```

## Web control panel

```bash
bb web           # http://127.0.0.1:7777
bb web 8080      # another port
```

Submit a job (source path, target language, format, optional mux), watch it run
with the engine's diagnostics streaming in, download the subtitle when it lands,
and switch providers from the same page — the panel writes the same config file
`provider use` does, so the CLI and the panel never disagree.

It binds to **loopback only, on purpose**: it runs commands and reads files on
the host, so it must not be reachable from off the machine. There is no auth —
do not put it behind a public listener or a tunnel.

Jobs live in memory for the life of the server; the artifacts it writes are the
same sidecar files the CLI produces, beside the source.

## Swapping providers

The active provider lives at `[:providers <port>]` in the config — exactly the key
the engine reads. Swap it and the engine picks it up on the next run, no code change:

```bash
vtranslate provider use mt deepl          # translator -> :deepl
vtranslate provider use asr openai-whisper
vtranslate provider list                  # confirm the active (*) provider
vtranslate run movie.mp4 pt-BR en         # translate to Brazilian Portuguese
```

### Keys live in `pass`, not in the environment

```bash
vtranslate provider key mt Venice/key     # translator's provider reads pass Venice/key
```

A **resolvable pass entry beats the env var**, so an old `VENICE_API_KEY` left
exported cannot shadow the real key — a failure mode that otherwise surfaces as
an opaque 401 from the provider. `provider list` shows which source won:

```
* venice   https://api.venice.ai/api/v1/chat/completions  [pass Venice/key]
  deepl    https://api-free.deepl.com/v2/translate        [VTRANSLATE_DEEPL_API_KEY UNSET]
```

The key belongs to the **provider**, not the port, so `provider key` writes it to
every port currently using that provider (Venice translating *and* digesting =
one key), and a port switching onto a provider inherits the entry it already has.

## Config + secrets

- **Location:** `$XDG_CONFIG_HOME/vtranslate/config.edn`, else `~/.config/vtranslate/config.edn`.
  Written `0600`.
- **Secrets are never stored in the config** — only `:secret-env`, the NAME of the env
  var holding the key. The engine resolves it from the environment at the HTTP boundary.
  `provider list` shows whether each provider's `:secret-env` is currently set.

## Engine bridge

`run` builds an EDN job spec, shells `clojure -M:run` in the engine (override the
location with `VTRANSLATE_ENGINE_DIR`), feeds the spec on stdin, and prints the
engine's EDN Result. Exit 0 on ok, 1 on err.

## Dev

```bash
bb vtranslate <subcommand> ...    # run via the bb task
```
