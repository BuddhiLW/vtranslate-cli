# Malli / trifecta coverage

## Contracts

`vtranslate.cli.schema` collects JobSpec, SubmitRequest, Job and ConfigPatch (reusing the existing boundary schemas), provider configuration/options/registry, local addon classpath configuration, subprocess invocation/result, route, dialog/options, output summary, store, job view, diagnostics, doctor report and HTTP response. Plugin payloads and error/result payloads remain intentionally open; this is structural validation, not a closed model of engine-owned domain data.

## Pure coverage

`trifecta-test` uses named golden cases under `test/golden`, test.check generators and predicates combining Malli validation with input/output relations. `contract` expands to deftrifecta with an `{:in :out}` observation wrapper.

Covered: job make/start/succeed/fail/log (including 500-line overflow), terminal?, duration-ms, mux-of, format-of, spec-inputs; runner/describe; config resolve-port, opts-key, clamp-threads, known-providers, ports-using, provider-secret-pass; job-spec build-job-spec/validate; web.schema/conform; output subtitle-path/video-path; engine resolve-engine-dir/parse-result; routes/match; dialog available/choose/command/parse-selection/interpret; classpath pinned-engine-sdeps/pinned-engine-command, engine-aliases/engine-sdeps/engine-command with environment stubbed; local-deps/make-source and aliases-for/preset-for; addon source?/checked-source, runner?/checked-runner, picker?/checked-picker; native-dialog make-picker/describe with injected presence predicate. Generated record constructors are not separately tested.

## Effects / remaining gaps

Existing config tests exercise filesystem permissions and stub pass/env resolution. Existing engine-boundary and runner tests exercise injected subprocess/engine behavior. Added refusing-engine and unavailable-file-picker contract assertions. No real subprocess, native dialog, HTTP server, network or credentials are used by the new tests.

Not newly covered by trifecta: config-path/default-config/read-user/effective/init!/set-path!/set-key!/env-set?/pass-entry?/set-provider-secret!/available-cores/set-asr-threads!/secret-source/provider-diagnostic/doctor-report/doctor/use-provider! (environment, filesystem, clock/secret dependencies); engine-dir/engine-invocation/run-job; engine-alias-env; read-local-deps/from-file; mutable store APIs; runner run-job!/submit!; server APIs; native on-path?; subprocess runner factories/checks and available?. These remain effectful or effect-producing boundaries, not claimed pure coverage. Private helpers are exercised transitively.

## Verification and bugs

Fresh JVM full suite: `timeout 900 clojure -Sdeps "$(cat local.deps.edn)" -M:test`: **156 tests, 355 assertions, 0 failures, 0 errors**. The new JVM alias supplies babashka libraries that are built into the normal bb runtime and discovers all existing test namespaces. No production bug found; corrected a nil-membership mistake in the new property before committing.
