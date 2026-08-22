(ns etzhayyim.yoro-supply.repo-test
  "**この repo に実際に commit されているファイル**を検査する。

  contract-test が『規則が落ちること』を fixture で見せるのに対し、こちらは
  『実物がその規則を通ること』を見る。加えて py/agent.cljc を実際に load して、
  README と manifest が宣言している決定核の不変条件（G1 SBT consent / G2 charter
  screening / G7 tithe 10% / G7+G11 署名なし settlement は intent 止まり）を
  実挙動で固定する —— descriptor が正しくても決定核が壊れれば actor の宣言は
  嘘になるからである。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cljs.reader :as reader]
            [etzhayyim.yoro-supply.contract :as c]
            [nbb.core]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def repo-root (.cwd js/process))

(defn- slurp* [rel] (.readFileSync fs (path/join repo-root rel) "utf8"))

(def manifest (reader/read-string (slurp* "manifest.edn")))
(def jsonld (js->clj (js/JSON.parse (slurp* "manifest.jsonld"))))
(def deploy (reader/read-string (slurp* "deploy/app-aozora.edn")))
(def cells
  (->> (.readdirSync fs (path/join repo-root "cells"))
       (filter #(str/ends-with? % ".edn"))
       (mapv #(reader/read-string (slurp* (str "cells/" %))))))
(def lexicons
  (->> (.readdirSync fs (path/join repo-root "lex"))
       (filter #(str/ends-with? % ".edn"))
       (mapv #(reader/read-string (slurp* (str "lex/" %))))))

(def agent-source (slurp* "py/agent.cljc"))

(def defn-names
  "py/agent.cljc の top-level defn 名。handler 実在検査の入力。"
  (set (map second (re-seq #"\(defn\s+([A-Za-z0-9_*!?<>=-]+)" agent-source))))

(def repo-data
  {:manifest manifest :jsonld jsonld :deploy deploy
   :cells cells :lexicons lexicons :defn-names defn-names})

;; 決定核を実際に load する（純関数のみ。network も LLM も呼ばない）。
;; nbb は top-level promise を await するので、後続の form からは
;; yoro-supply.cljc.agent の var が見える。
(nbb.core/load-file (path/join repo-root "py" "agent.cljc"))

;; ── descriptor: 全規則 ──────────────────────────────────────────────────────

(deftest the-committed-descriptor-has-no-violations
  (let [violations (c/check repo-data)]
    (is (= [] violations)
        (str "違反 " (count violations) " 件:\n"
             (str/join "\n" (map #(str "  " (:rule %) " — " (:detail %)) violations))))))

;; ── descriptor: 個別(落ちたとき、どの面が割れたかを名指しするための細分)──────

(deftest deploy-and-jsonld-name-the-same-did
  (is (= (get jsonld "id") (:deployment/actor-did deploy))))

(deftest manifest-cells-and-cell-files-are-a-bijection
  (is (= (c/manifest-cell-ids manifest) (set (map :cell/id cells)))))

(deftest deploy-allowlist-equals-the-cell-set
  (is (= (set (map c/cell-capability (c/manifest-cell-ids manifest)))
         (set (map name (:deployment/capability-allowlist deploy))))))

(deftest manifest-lex-and-lexicon-files-are-a-bijection
  (is (= (c/manifest-lex-ids manifest) (c/lexicon-short-ids lexicons))))

(deftest every-cell-handler-exists-in-the-agent
  (is (seq cells) "cell が 1 つも無いなら、この検査は空虚")
  (doseq [cl cells]
    (is (contains? defn-names (c/handler-fn-name (:cell/handler cl)))
        (str "cell " (:cell/id cl) " → " (c/handler-fn-name (:cell/handler cl))))))

(deftest every-cell-state-graph-is-well-formed
  (is (= [] (vec (c/check-state-graph cells)))))

(deftest every-llm-cell-is-murakumo-only
  (let [llm-cells (filter :cell/llm cells)]
    (is (seq llm-cells) "LLM cell が 1 つも無いなら、この検査は空虚")
    (is (= [] (vec (c/check-llm llm-cells))))))

(deftest the-deploy-manifest-path-exists
  (is (.existsSync fs (path/join repo-root (:deployment/manifest-path deploy)))))

(deftest every-cell-entry-file-exists
  (doseq [cl cells]
    (is (.existsSync fs (path/join repo-root (:cell/entry cl)))
        (str "cell " (:cell/id cl) " entry " (:cell/entry cl)))))

;; ── 決定核: py/agent.cljc の実挙動 ──────────────────────────────────────────

(deftest a-stranger-without-sbt-cannot-place-an-order
  (testing "G1: active Adherent SBT を持たない DID の発注は refused"
    (let [po (yoro-supply.cljc.agent/create-purchase-order
              "did:web:stranger" "s1" ["steel"] [10] "2026-09-01" {})]
      (is (= "refused" (get po "state")))
      (is (str/includes? (get po "reason") "SBT")))))

(deftest an-active-sbt-member-can-place-an-order
  (let [reg {"did:web:m" {"active" true}}
        po (yoro-supply.cljc.agent/create-purchase-order
            "did:web:m" "s1" ["steel"] [10] "2026-09-01" reg)]
    (is (= "placed" (get po "state")))))

(deftest settlement-splits-exactly-ten-percent-tithe
  (testing "G7: tithe は gross の 10%、payout との和は gross に一致"
    (let [s (yoro-supply.cljc.agent/build-settlement-intent 500000000)]
      (is (= 50000000 (get s "titheMinor")))
      (is (= 450000000 (get s "supplierPayoutMinor")))
      (is (= "usdc-base-l2" (get s "rail"))))
    (doseq [gross [999 1 10000 123456789]]
      (let [s (yoro-supply.cljc.agent/build-settlement-intent gross)]
        (is (= gross (+ (get s "titheMinor") (get s "supplierPayoutMinor")))
            (str "gross " gross " が tithe+payout に分解されない"))))))

(deftest settlement-stops-at-intent-without-member-signature
  (testing "G7+G11: member 署名なしの settlement は intent 止まり —— executed に届かない"
    (let [s (yoro-supply.cljc.agent/build-settlement-intent 1000000)]
      (is (= "intent" (get s "state")))
      (is (= "" (get s "memberSigRef"))))))

(deftest settlement-executes-only-with-member-signature
  (let [s (yoro-supply.cljc.agent/build-settlement-intent 1000000 "0xmembersig")]
    (is (= "executed" (get s "state")))))

(deftest charter-screening-fails-when-any-supplier-fails
  (testing "G2: 1 社でも charter-compliance が failed なら charter_passed は false"
    (let [out (yoro-supply.cljc.agent/handle-supplier-selection
               {"materials" ["steel"]
                "suppliers" [{"supplierDid" "s1" "capabilities" ["steel"]
                              "charter-compliance" "passed"}
                             {"supplierDid" "s2" "capabilities" ["steel"]
                              "charter-compliance" "failed"}]})]
      (is (false? (get out "charter_passed"))))))
