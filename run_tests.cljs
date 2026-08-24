#!/usr/bin/env nbb
;; run_tests.cljs — yoro-supply actor contract の検査。
;;
;;   nbb --classpath src:test run_tests.cljs
;;
;; manifest.edn ↔ cells/*.edn ↔ lex/*.edn ↔ deploy/app-aozora.edn ↔
;; py/agent.cljc の 5 面一致と、決定核（SBT gate / tithe 10% / intent 停止 /
;; charter screening）の実挙動をここで固定する。2026-08-22 までこの repo には
;; これらを検査するものが何も無かった。
;; workspace の規則で script host は nbb（.ts / .mjs / .sh の新規作成は禁止）。
(ns run-tests
  (:require [clojure.test :as t]
            [etzhayyim.yoro-supply.contract-test]
            [etzhayyim.yoro-supply.repo-test]))

(def green-marker
  "scripts/maturity-loop/mutations.edn の `:green-marker`。
  全部緑のときだけ出る —— 出力に現れるかどうかで mutation が噛んだかを判定する
  ので、緑でないときに印字してはならない。"
  "yoro-supply actor contract: all green")

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (if (t/successful? m)
    (println (str "\n" green-marker))
    (do (println "\nyoro-supply actor contract: FAILED")
        (js/process.exit 1))))

;; Declared exclusion — yoro-supply.cljc.test-agent (py/test_agent.cljc) is NOT
;; run here, and cannot be: it is a bb-hosted legacy port (`#!/usr/bin/env bb`,
;; clojure.java.io, and a `load-file` resolved from `babashka.file` — nbb has no
;; load-file, and `py/agent.cljc` does not sit on a path its ns maps to, so this
;; runner cannot load either file). Measured 2026-08-24: `bb py/test_agent.cljc`
;; — Ran 12 tests containing 20 assertions, 0 failures, 0 errors. bb is a
;; retired script host workspace-wide (ADR-2607173000), so the standing
;; follow-up is to port the agent tests into test/ as portable .cljc and name
;; them in the require + run-tests call above; until then this comment is the
;; declaration that the exclusion is known, why, and what still runs it.
(t/run-tests 'etzhayyim.yoro-supply.contract-test
             'etzhayyim.yoro-supply.repo-test)
