# ADR-0001: the suite loads `.kotoba` by path on nbb, and refuses a zero-test run

**Status**: accepted · 2026-09-11 · itonami-maturity-improve, axis-docs

## Context

On 2026-09-10 every Clojure source here was renamed to `.kotoba` (commit
`2cfae9d`, owner instruction: rename first, let the compiler's refusals be the
work list). The runner still required its test namespaces by name:

```
(:require [etzhayyim.yoro-supply.contract-test]
          [etzhayyim.yoro-supply.repo-test])
```

nbb's classpath resolver maps a namespace only to `.cljs` / `.cljc`, so at
`2a6c9be` the suite stopped with `Could not find namespace:
etzhayyim.yoro-supply.contract-test`, exit 1, zero tests — and stayed that way
until this ADR. `repo_test` also read `py/agent.cljc` by literal path, a file
that no longer existed.

The failure was loud (exit 1). The sibling repositories that run their
suites through the JVM `:test` alias got the quiet form of the same defect:
`Ran 0 tests containing 0 assertions. 0 failures` with exit 0. Whether this
repository's next loader failure is loud or quiet is not something the
current shape decides.

`amu check` does not accept either source today (`contract.kotoba`:
`:kotoba/project-link-failed`, no `:export` vector; `py/agent.kotoba`:
`:kotoba/source-read-failed`). The rename commit says the repository is not
claimed to compile as Kotoba, so the suite cannot wait for the compiler.

## Decision

1. **The runner loads the three sources by path with `nbb.core/load-file`,
   in dependency order** (contract → contract_test → repo_test), from the
   repository root. No `--classpath` is needed. nbb awaits each top-level
   promise, so later forms see earlier namespaces, and `repo_test`'s
   `(:require [etzhayyim.yoro-supply.contract :as c])` resolves against the
   already-loaded namespace.

   Not chosen: staging copies of the sources under `.cljc` and running those
   (what `actor-crew` does). It works, but it executes a copy; a reader who
   opens the `.kotoba` file and the staged file can be looking at two trees.
   Loading by path executes the committed bytes.

   Not chosen: reverting the rename. The rename was an instruction.

2. **A run that collects zero tests exits 2 and prints `REFUSED`, never the
   green marker.** `0` means at least one test ran and none failed; `1` means
   something failed; `2` means the suite could not be measured. Provoked by
   pointing `run-tests` at a namespace with no `deftest`.

3. **`py/test_agent.kotoba` stays dormant** and the runner says why in
   place: it is bb-hosted (`clojure.java.io`, `System/getProperty
   "babashka.file"`), and bb is a retired script host. The earlier comment
   claiming "nbb has no `load-file`" was wrong — `repo_test` had been using it
   since 2026-08-22 — and is replaced. The decision-core cases the README
   relies on (G1 / G2 / G7 / G11) are covered in `test/` against the real
   agent; the handler-shape cases are the standing follow-up.

4. **`kotoba/deploy.sh` is documented as not runnable**, not repaired: it
   names `kotoba/ingest_mcp.cljc`, which has never been in this repository.
   Writing that ingest script is a separate decision about the kotoba node
   contract, not a test-loading one.

## Consequences

- `nbb run_tests.kotoba` runs 33 tests / 52 assertions green at this commit;
  two hand-applied mutations (tithe 1000→100 bps; a handler renamed only in
  its cell) each fail exactly the tests that name them. The superproject's
  `scripts/maturity-loop/mutations.edn` entry is repointed at the `.kotoba`
  paths so the loop's nine mutations keep biting.
- `FAIL in (...)` lines name `run_tests.kotoba` with the test file's line
  number; sci attributes `load-file`d forms to the caller. Known, tolerated.
- The README's gate table is now a copy of `manifest.edn`'s `:actor/gates`.
  The list it carried since the scaffold (G1 "supplier firmware open-source",
  …) named gates that no cell, test, or agent line referred to.
- Nothing here changes what the compiler says. When `amu check` accepts
  these files, the runner can stay as it is or hand off to `amu test`; that
  is the next decision, not this one.
