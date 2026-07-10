#!/usr/bin/env bb
;; Clojure port of test_agent.cljc for yoro-supply agent.
(ns yoro-supply.cljc.test-agent
  "yoro-supply 綾取供給 — agent cell tests (no kotoba host, no network, no LLM).

  ADR-2605250850 Phase 2. Exercises the 5 handlers + settlement + gates with
  injected functions so the suite runs offline (Murakumo-only invariant
  untouched; G5).

  Run:  bb --classpath 20-actors 20-actors/yoro-supply/py/test_agent.cljc"
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [clojure.java.io :as io]))

;; NOTE: bb resolves ns hyphens -> underscores in classpath path lookup, but the
;; actor dir is named yoro-supply (hyphenated). We load-file the agent directly
;; so it is registered in its own ns, then require it by ns name.
(load-file (.getCanonicalPath
            (io/file (io/file (System/getProperty "babashka.file")) ".." "agent.cljc")))
(require '[yoro-supply.cljc.agent :as agent])

;; ── Supplier selection ───────────────────────────────────────────────────────
(deftest test-supplier-selection-ranks-by-capability
  (testing "supplier selection ranks by capability match"
    (let [out (agent/handle-supplier-selection
               {"materials" ["steel H-beam 200mm" "concrete 25 MPa"]
                "suppliers" [{"supplierDid" "concrete-s1"
                              "capabilities" ["concrete" "cement"]
                              "charter-compliance" "passed"}
                             {"supplierDid" "steel-s1"
                              "capabilities" ["steel" "h-beam"]
                              "charter-compliance" "passed"}]})]
      (is (= 2 (count (get out "candidates"))))
      (is (#{"concrete-s1" "steel-s1"} (get-in out ["candidates" 0 "supplierDid"]))))))

;; ── SBT membership gate ──────────────────────────────────────────────────────
(deftest test-sbt-gate-refuses-non-member
  (testing "non-SBT member refused"
    (let [order (agent/create-purchase-order "did:web:stranger" "s1"
                                              ["steel"] [10] "2026-07-01" {})]
      (is (= "refused" (get order "state"))))))

(deftest test-sbt-gate-accepts-active-member
  (testing "active SBT member accepted"
    (let [reg {"did:web:m" {"active" true}}
          order (agent/create-purchase-order "did:web:m" "s1"
                                             ["steel"] [10] "2026-07-01" reg)]
      (is (= "placed" (get order "state"))))))

;; ── Charter screening ─────────────────────────────────────────────────────────
(deftest test-charter-screening-all-passed
  (testing "charter screening marked passed"
    (let [out (agent/handle-supplier-selection
               {"materials" ["materials"]
                "suppliers" [{"supplierDid" "s1"
                              "capabilities" ["materials"]
                              "charter-compliance" "passed"}]})]
      (is (true? (get out "charter_passed"))))))

(deftest test-charter-screening-one-failed
  (testing "charter screening marked failed if any supplier fails"
    (let [out (agent/handle-supplier-selection
               {"materials" ["materials"]
                "suppliers" [{"supplierDid" "s1"
                              "capabilities" ["materials"]
                              "charter-compliance" "passed"}
                             {"supplierDid" "s2"
                              "capabilities" ["materials"]
                              "charter-compliance" "failed"}]})]
      (is (false? (get out "charter_passed"))))))

;; ── Manufacturing track ────────────────────────────────────────────────────────
(deftest test-manufacture-track-records-progress
  (testing "manufacture track records progress datom"
    (let [out (agent/handle-manufacture-track
               {"stage" "assembly"
                "pct" 45
                "ipfsPin" "bafkqzzz"
                "now" "2026-06-01T12:00:00Z"})]
      (is (= "assembly" (get-in out ["progress" "stage"])))
      (is (= 45 (get-in out ["progress" "pct"]))))))

;; ── Shipment ───────────────────────────────────────────────────────────────────
(deftest test-shipment-arranges-carrier
  (testing "shipment arranges carrier + tracking"
    (let [out (agent/handle-shipment
               {"carrier" "FedEx"
                "trackingNumber" "1234567890"
                "eta" "2026-06-15T00:00:00Z"
                "customsCleared" true
                "now" "2026-06-01T14:30:00Z"})]
      (is (= "FedEx" (get-in out ["shipment" "carrier"])))
      (is (= "1234567890" (get-in out ["shipment" "trackingNumber"]))))))

;; ── Delivery verify ────────────────────────────────────────────────────────────
(deftest test-delivery-verify-witness-quorum
  (testing "delivery verify records witness quorum (default witnessDids)"
    (let [out (agent/handle-delivery-verify
               {"witnessDids" ["did:web:w1" "did:web:w2"]
                "qcResult" "pass"
                "now" "2026-06-15T10:00:00Z"})]
      (is (= 2 (count (get-in out ["attestation" "witnessDids"])))))))

(deftest test-delivery-verify-witness-fn-injected
  (testing "delivery verify uses injected witness fn seam"
    (let [out (agent/handle-delivery-verify
               {"qcResult" "pass"
                "now" "2026-06-15T10:00:00Z"}
               (fn [] ["did:web:fake1" "did:web:fake2"]))]
      (is (= 2 (count (get-in out ["attestation" "witnessDids"]))))
      (is (= "did:web:fake1" (get-in out ["attestation" "witnessDids" 0]))))))

;; ── Settlement intent ──────────────────────────────────────────────────────────
(deftest test-settlement-tithe-split
  (testing "10% tithe split + stops at intent (G7)"
    (let [s (agent/build-settlement-intent 500000000)]
      (is (= 50000000 (get s "titheMinor")))
      (is (= 450000000 (get s "supplierPayoutMinor")))
      (is (= "intent" (get s "state")))
      (is (= "usdc-base-l2" (get s "rail"))))))

(deftest test-settlement-executed-only-with-member-sig
  (testing "settlement executes only with member signature (G11)"
    (let [s (agent/build-settlement-intent 1000000 "0xmembersig")]
      (is (= "executed" (get s "state"))))))

(deftest test-settlement-stops-at-intent-without-sig
  (testing "settlement stops at intent without sig (G7/G11)"
    (let [s (agent/build-settlement-intent 1000000)]
      (is (= "intent" (get s "state")))
      (is (= "" (get s "memberSigRef"))))))

;; ── Entry point ────────────────────────────────────────────────────────────────
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (clojure.test/run-tests 'yoro-supply.cljc.test-agent)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
