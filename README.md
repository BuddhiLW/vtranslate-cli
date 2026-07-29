# vtranslate-cli

A [babashka](https://babashka.org) CLI over **vtranslate-engine**. It edits the
config that selects providers, then shells the engine to run a job. The engine
owns all domain logic; this is a thin driving adapter (argv → config/spec → engine).

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

## Swapping providers

The active provider lives at `[:providers <port>]` in the config — exactly the key
the engine reads. Swap it and the engine picks it up on the next run, no code change:

```bash
vtranslate provider use mt deepl          # translator -> :deepl
vtranslate provider use asr openai-whisper
vtranslate provider list                  # confirm the active (*) provider
vtranslate run movie.mp4 pt-BR en         # translate to Brazilian Portuguese
```

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
