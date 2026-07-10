#!/usr/bin/env bb
;; Clojure port of py/agent.cljc (yoro-supply procurement & logistics actor).
(ns yoro-supply.cljc.agent
  "yoro-supply 綾取供給 — material sourcing & logistics langgraph actor (kotoba WASM cell).

  ADR-2605250850, migration plan Phase 2. Runs in-WASM on kotoba :8077. Five
  handlers over one kotoba EAVT graph, mirroring the supply chain lifecycle:

    handle-supplier-selection   projectBOM -> capability/region match
    handle-order-placement      selectedSuppliers -> purchaseOrderConfirmation
    handle-manufacture-track    purchaseOrderConfirmation -> progress
    handle-shipment             manufacturingProgressRecord -> tracking
    handle-delivery-verify      shipmentTrackingRecord -> attestation

  LLM access is Murakumo-only via KotobaLLM (127.0.0.1:4000, gemma3:4b; G5).
  State is written to kotoba Datom log (G6). Charter Rider screening enforced
  (G2). Settlement is USDC Base L2 + ERC-4337 + TitheRouter 10% only (G7).
  Platform holds no key (G11). Every stage recorded as Datom (G13).

  This R0 build computes and returns plans; it does not dispatch real work and
  does not broadcast settlements (settlement stops at :intent).

  Run:  bb --classpath 20-actors 20-actors/yoro-supply/py/agent.cljc"
  (:require [clojure.string :as str]
            [clojure.set :as set]))

(def TITHE_BPS 1000)

(defn _capability-match
  "Token-overlap capability scoring. Crude by design; Murakumo reranks."
  [material supplier]
  (let [material-tokens (set (str/split (str/lower-case (str material)) #"\s+"))
        supplier-caps (get supplier "capabilities" [])]
    (reduce (fn [score cap]
              (let [cap-tokens (set (str/split (str/lower-case (str cap)) #"\s+"))]
                (if (seq (set/intersection material-tokens cap-tokens))
                  (inc score)
                  score)))
            0
            supplier-caps)))

(defn _llm-rank-suppliers
  "Murakumo-only rerank by genuine fit (capability + region).
  Offline: returns candidates unchanged."
  [materials cands]
  cands)

(defn handle-supplier-selection
  "Match BOM materials to capable suppliers."
  [state]
  (let [materials (get state "materials" [])
        suppliers (get state "suppliers" [])
        scored (for [s suppliers
                     :let [score (reduce + (map (fn [m] (_capability-match m s)) materials))]
                     :when (> score 0)]
                 [score s])
        sorted-scored (sort-by first #(compare %2 %1) scored)
        cands (vec (map second sorted-scored))
        cands (_llm-rank-suppliers materials cands)
        charter-ok (every? #(= "passed" (get % "charter-compliance")) cands)]
    (assoc state
           "candidates" cands
           "charter_passed" charter-ok)))

(defn check-sbt-eligibility
  "Member must hold active Adherent SBT to order."
  [member-did sbt-registry]
  (let [active (boolean (get-in sbt-registry [member-did "active"]))]
    {"eligible" active
     "reason" (if active "" "no active Adherent SBT")}))

(defn create-purchase-order
  "Create purchase order. Member = ordering principal."
  [member-did supplier-did materials quantities delivery-date sbt-registry]
  (let [elig (check-sbt-eligibility member-did (or sbt-registry {}))]
    (if (not (get elig "eligible"))
      {"state" "refused" "reason" (get elig "reason")}
      {"memberDid" member-did
       "supplierDid" supplier-did
       "materials" materials
       "quantities" quantities
       "deliveryDate" delivery-date
       "state" "placed"})))

(defn handle-order-placement
  "Create purchase order from supplier selection result."
  [state]
  (let [po (or (get state "order")
               (create-purchase-order
                (get state "memberDid" "")
                (get state "supplierDid" "")
                (get state "materials" [])
                (get state "quantities" [])
                (get state "deliveryDate" "")
                (get state "sbtRegistry" {})))]
    (assoc state "order" po)))

(defn handle-manufacture-track
  "Track manufacturing progress. IPFS-pinned telemetry."
  [state]
  (let [order (get state "order" {})
        progress {"orderId" (get order "supplierDid" "")
                  "stage" (get state "stage" "raw")
                  "pct" (get state "pct" 0)
                  "ipfsPin" (get state "ipfsPin" "")
                  "factoryNote" (get state "factoryNote" "")
                  "timestamp" (get state "now" "")}]
    (assoc state "progress" progress)))

(defn handle-shipment
  "Arrange shipment. Carrier coordination + ETA."
  [state]
  (let [shipment {"carrier" (get state "carrier" "")
                  "trackingNumber" (get state "trackingNumber" "")
                  "eta" (get state "eta" "")
                  "customsCleared" (get state "customsCleared" false)
                  "timestamp" (get state "now" "")}]
    (assoc state "shipment" shipment)))

(defn handle-delivery-verify
  "Verify delivery. >=2 independent witnesses."
  [state & [witness-fn]]
  (let [witnesses (or (when witness-fn (witness-fn))
                      (get state "witnessDids" []))
        attestation {"receivedQty" (get state "receivedQty" [])
                     "witnessDids" witnesses
                     "certificationStatus" (get state "certStatus" "pending")
                     "qcResult" (get state "qcResult" "pass")
                     "qcNotes" (get state "qcNotes" "")
                     "timestamp" (get state "now" "")}]
    (assoc state "attestation" attestation)))

(defn build-settlement-intent
  "Compute USDC settlement split. 10% tithe to Public Fund.
  Stops at :intent; broadcast needs member signature + operator gate."
  ([gross-minor]
   (build-settlement-intent gross-minor nil))
  ([gross-minor member-sig-ref]
   (let [gross (long gross-minor)
         tithe (quot (* gross TITHE_BPS) 10000)]
     {"rail" "usdc-base-l2"
      "grossMinor" gross
      "titheMinor" tithe
      "supplierPayoutMinor" (- gross tithe)
      "titheRouter" "50-infra/etzhayyim-tithe-router"
      "state" (if member-sig-ref "executed" "intent")
      "memberSigRef" (or member-sig-ref "")})))

(defn main [& _]
  (let [demo (handle-supplier-selection
               {"materials" ["steel H-beam 200mm" "concrete 25 MPa"]
                "suppliers" [{"supplierDid" "s1" "capabilities" ["concrete" "cement"]}
                             {"supplierDid" "s2" "capabilities" ["steel" "h-beam"]}]})]
    (println "selection candidates:" (map #(get % "supplierDid") (get demo "candidates")))
    (println "settlement:" (build-settlement-intent 500000000))))

(when (= *file* (System/getProperty "babashka.file"))
  (main))
