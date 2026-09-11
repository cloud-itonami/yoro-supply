# yoro-supply — operator quickstart

Everything below was executed on 2026-09-11 against the tree this file landed
in (base `2a6c9be`, the 2026-09-10 `.kotoba` rename). Where a command's output
is quoted, it is what the command printed, not what it is supposed to print.
Absolute paths are the only thing elided.

## What this repository is

`yoro-supply` (綾取供給) is the material sourcing and logistics Tier-B actor
for etzhayyim construction projects: `did:web:etzhayyim.com:yoro-supply`. It
takes a `projectBOM` and moves it through five langgraph cells —
`supplier_selection → order_placement → manufacture_track → shipment →
delivery_verify` — each an EDN descriptor under `cells/` naming a handler in
`py/agent.kotoba`.

The repository has **five faces that must agree**: `manifest.edn`,
`cells/*.edn`, `lex/*.edn`, `deploy/app-aozora.edn`, and `py/agent.kotoba`.
`src/etzhayyim/yoro_supply/contract.kotoba` holds the rules that say what
"agree" means; the suite in `test/` applies them to the committed files and
then loads the agent for real to pin the decision core (G1 SBT gate, G2
charter screening, G7 tithe 10 %, G7+G11 settlement stops at intent).

It computes and returns plans. Nothing here dispatches work, calls an LLM,
or broadcasts a settlement.

## 0. Prerequisites

- `nbb` on `PATH` (measured with v1.5.212).
- Nothing else to install. `nbb.edn` pins `kotoba-lang/text` by sha and nbb
  fetches it on first run (`Downloading dependencies... Done.` — once).
  ⚠ Two directories matter and they are not the same one. nbb resolves
  `nbb.edn` relative to the **script's directory**; the runner and the tests
  read the repository's files relative to the **current directory**. So every
  command below is run from the repository root with a script path inside it.
  Both deviations were provoked: a copy of `run_tests.kotoba` in `/tmp` run
  from the repository root stops with `Could not find namespace:
  kotoba.lang.text`; run from `/tmp` it stops with `ENOENT ...
  /tmp/src/etzhayyim/yoro_supply/contract.kotoba`.

## 1. Run the contract suite

```
nbb run_tests.kotoba
```

Observed:

```
Testing etzhayyim.yoro-supply.contract-test

Testing etzhayyim.yoro-supply.repo-test

Ran 33 tests containing 52 assertions.
0 failures, 0 errors.

yoro-supply actor contract: all green
```

Exit codes:

| exit | meaning |
|---|---|
| 0 | the suite ran, at least one test executed, nothing failed |
| 1 | the suite ran and something failed |
| 2 | **REFUSED** — zero tests were collected. Never a pass |

`2` was provoked by pointing `run-tests` at a namespace with no `deftest`
(`Ran 0 tests containing 0 assertions.` followed by the REFUSED line). It
exists because between 2026-09-10 and 2026-09-11 this suite did not run at
all — see step 2 — and a runner that collects nothing must not print the
green marker.

The `--classpath src:test` the old header asked for is no longer needed: the
runner loads the three source files by path (`nbb.core/load-file`), in
dependency order, because nbb's classpath resolver maps a namespace only to
`.cljs` / `.cljc` and every source here is `.kotoba`. A consequence you will
see on a failure: the `FAIL in (...)` line names `run_tests.kotoba` with the
**test file's** line number — sci attributes forms from `load-file` to the
caller. The test name is right; look it up in `test/`.

## 2. What happened at the rename, and how to check it never recurs

On 2026-09-10 every `.clj*` here became `.kotoba` (commit `2cfae9d`). The
runner still said `(:require [etzhayyim.yoro-supply.contract-test])`, which
nbb cannot resolve for a `.kotoba` file, so at `2a6c9be`:

```
kbb --backend sci --classpath src:test run_tests.kotoba
Error: Could not find namespace: etzhayyim.yoro-supply.contract-test
```

exit 1, zero tests. That is the loud form. The quiet form — a loader that
finds zero files and reports `0 failures` with exit 0 — is what the JVM
`:test` alias did in the sibling repositories after the same rename. The
exit-2 floor in step 1 is the guard against the quiet form here.

## 3. Exercise the decision core by hand

The suite loads `py/agent.kotoba` and calls it; you can do the same from the
repository root:

```
kbb --backend sci -e '(require (quote [nbb.core :as nbb]))
(.then (nbb/load-file "py/agent.kotoba")
  (fn [_]
    (let [a (find-ns (quote yoro-supply.cljc.agent))
          settle (ns-resolve a (quote build-settlement-intent))
          order  (ns-resolve a (quote create-purchase-order))]
      (println :settlement (select-keys (settle 500000000)
                             ["state" "titheMinor" "supplierPayoutMinor" "rail" "memberSigRef"]))
      (println :stranger (select-keys (order "did:web:stranger" "s1" ["steel"] [10] "2026-09-01" {})
                           ["state" "reason"]))
      (println :member (select-keys (order "did:web:m" "s1" ["steel"] [10] "2026-09-01"
                                           {"did:web:m" {"active" true}})
                         ["state"])))))'
```

Observed:

```
:settlement {state intent, titheMinor 50000000, supplierPayoutMinor 450000000, rail usdc-base-l2, memberSigRef }
:stranger {state refused, reason no active Adherent SBT}
:member {state placed}
```

Three invariants in three lines: the tithe is exactly 10 % of gross and the
two parts sum back to it (G7); a settlement without a member signature is an
`intent`, not `executed` (G7+G11); a DID without an active Adherent SBT is
`refused` before any order exists (G1).

## 4. Check that the suite still bites

A suite that stopped measuring looks exactly like a suite that passed. Before
trusting a green, break something and watch it go red. Two of the nine
registered mutations (`scripts/maturity-loop/mutations.edn` in the
superproject), applied by hand:

**Tithe shrinks** — `py/agent.kotoba`, `(def TITHE_BPS 1000)` → `(def TITHE_BPS 100)`:

```
FAIL in (settlement-splits-exactly-ten-percent-tithe) (.../run_tests.kotoba:112)
G7: tithe は gross の 10%、payout との和は gross に一致
expected: (= 50000000 (get s "titheMinor"))
  actual: (not (= 50000000 5000000))
...
Ran 33 tests containing 52 assertions.
2 failures, 0 errors.

yoro-supply actor contract: FAILED
```

**Handler renamed only in the cell** — `cells/supplier_selection.edn`,
`"handle_supplier_selection"` → `"handle_supplier_pick"`:

```
FAIL in (the-committed-descriptor-has-no-violations) (.../run_tests.kotoba:53)
違反 1 件:
  :handlers/missing-defn — cell supplier_selection names handler "handle_supplier_pick" but the agent defines no "handle-supplier-pick"
...
FAIL in (every-cell-handler-exists-in-the-agent) (.../run_tests.kotoba:75)
...
2 failures, 0 errors.
```

Both exit 1. Restore the files afterwards (`git checkout py/agent.kotoba
cells/supplier_selection.edn`) and step 1 is green again — observed.

## 5. Read the data the actor would run on

`kotoba/schema.edn` and `kotoba/seed.edn` are plain EDN; the reader is the
check:

```
kbb --backend sci -e '(require (quote [cljs.reader :as r])) (require (quote ["node:fs" :as fs]))
(let [s (r/read-string (.readFileSync fs "kotoba/seed.edn" "utf8"))
      sc (r/read-string (.readFileSync fs "kotoba/schema.edn" "utf8"))]
  (println :seed (count s) (frequencies (map (fn [m] (some namespace (keys m))) s)))
  (println :schema-attrs (count sc) (frequencies (map (fn [m] (namespace (:db/ident m))) sc))))'
```

Observed:

```
:seed 7 {supplier 3, bom 1, supplier-selection 1, sbt 2}
:schema-attrs 60 {delivery-verified 10, settlement 9, purchase-order 8, bom 4, supplier-selection 5, manufacturing-progress 7, shipment-tracking 8, supplier 7, sbt 2}
```

The three suppliers are `:supplier/sourcing :representative` — named Japanese
manufacturers used as R0 stand-ins, not counterparties with an agreement.

## 6. Where things live

| path | what it is |
|---|---|
| `manifest.edn` | gen-3 actor manifest: gates, cells, lexicons (canonical) |
| `manifest.jsonld` | gen-1 manifest; kept because the DID must agree across both |
| `cells/*.edn` | one descriptor per cell: state graph, handler, LLM endpoint, gates |
| `lex/*.edn` | the six `com.etzhayyim.supply.*` lexicons the cells write |
| `deploy/app-aozora.edn` | the deployment record; its capability allowlist must equal the cell set |
| `py/agent.kotoba` | the five handlers + settlement, loaded for real by the suite |
| `src/etzhayyim/yoro_supply/contract.kotoba` | the five-face rules, pure functions over the read files |
| `test/etzhayyim/yoro_supply/contract_test.kotoba` | the rules shown failing on fixtures |
| `test/etzhayyim/yoro_supply/repo_test.kotoba` | the rules applied to the committed files + decision core |
| `run_tests.kotoba` | the runner in step 1 |
| `kotoba/schema.edn`, `kotoba/seed.edn` | EAVT schema (60 attributes) and R0 seed (7 entities) |

## Known gaps

- **The sources are not claimed to compile as Kotoba.** The rename commit
  says so; the compiler's refusals are the work list. Measured 2026-09-11 with
  `amu check`: `contract.kotoba` → `:kotoba/project-link-failed` ("project
  module requires an explicit :export vector"); `py/agent.kotoba` →
  `:kotoba/source-read-failed` ("source reader rejected input"). The suite
  above executes the files on nbb; it proves the actor contract holds, not
  Kotoba conformance.
- **`kotoba/deploy.sh` cannot complete.** Run it and it stops at the health
  check (`kotoba node not reachable at http://127.0.0.1:8077`, exit 1) —
  observed; but past that it calls `python3 kotoba/ingest_mcp.cljc`, a file
  that is not in this repository and never was (`git log --all` finds nothing).
  Treat it as a description of the intended sequence, not a runnable step.
- **`py/test_agent.kotoba` does not run here.** It is a bb-hosted port
  (`clojure.java.io`, `System/getProperty "babashka.file"`); kbb -M:is a retired
  script host. Its decision-core cases are covered by `repo_test.kotoba`; its
  handler-shape cases (ranking, order placement, tracking) are not, and porting
  them into `test/` is the standing follow-up.
- **The cells name `gemma3:4b` at `127.0.0.1:4000`.** The workspace's rule is
  to resolve the model through the `murakumo-main` alias rather than pin a
  model id; the suite only checks that the endpoint stays on localhost (G5).
