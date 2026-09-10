(ns etzhayyim.yoro-supply.contract-test
  "規則が**実際に落ちる**ことを fixture で見せる。

  repo-test が『実物が規則を通ること』を見るのに対し、こちらは『規則が壊れた
  入力で本当に violation を返すこと』を見る。この向きのテストが無いと、規則の
  実装を骨抜きにしても（:when に false を挟むなど）実物は緑のままなので、
  誰も気づかない —— scripts/maturity-loop の mutation はまさにそこを撃つ。"
  (:require [clojure.test :refer [deftest is testing]]
            [etzhayyim.yoro-supply.contract :as c]))

;; ── fixture ─────────────────────────────────────────────────────────────────
;; 実物と同型の最小 descriptor。fixture 自身が check を通ることを最初に固定する
;; —— 通らない fixture の上の「落ちるテスト」は何も証明しない。

(def manifest
  {:actor/id "yoro-supply"
   :actor/gates [{:gate/id "G5"} {:gate/id "G10"}]
   :actor/cells [{:cell/id "supplier_selection" :cell/kind :langgraph :cell/runtime :wasm}
                 {:cell/id "delivery_verify" :cell/kind :langgraph :cell/runtime :wasm}]
   :actor/lex [{:lex/id "supplierSelection"} {:lex/id "deliveryVerified"}]})

(def jsonld {"id" "did:web:fixture.example.com:yoro-supply"})

(def deploy
  {:deployment/actor-did "did:web:fixture.example.com:yoro-supply"
   :deployment/approval-required true
   :deployment/capability-allowlist [:yoro-supply.supplier-selection
                                     :yoro-supply.delivery-verify]})

(def cells
  [{:cell/id "supplier_selection" :cell/kind :langgraph :cell/runtime :wasm
    :cell/handler "handle_supplier_selection"
    :cell/gates ["G5"]
    :cell/llm {:provider :murakumo :endpoint "127.0.0.1:4000"}
    :cell/state-graph {:nodes [:a :b] :entry :a :edges [[:a :b] [:b :end]]}}
   {:cell/id "delivery_verify" :cell/kind :langgraph :cell/runtime :wasm
    :cell/handler "handle_delivery_verify"
    :cell/gates ["G10"]
    :cell/llm {:provider :murakumo :endpoint "127.0.0.1:4000"}
    :cell/state-graph {:nodes [:x] :entry :x :edges [[:x :end]]}}])

(def lexicons
  [{:lexicon 1 :id "com.etzhayyim.yorosupply.supplierSelection"}
   {:lexicon 1 :id "com.etzhayyim.yorosupply.deliveryVerified"}])

(def defn-names #{"handle-supplier-selection" "handle-delivery-verify"})

(def fixture
  {:manifest manifest :jsonld jsonld :deploy deploy
   :cells cells :lexicons lexicons :defn-names defn-names})

(defn- rules-of [violations] (set (map :rule violations)))

;; ── 緑の側 ──────────────────────────────────────────────────────────────────

(deftest the-fixture-descriptor-passes
  (is (= [] (c/check fixture))))

;; ── 落ちる側(規則 1 つにつき最低 1 つ)────────────────────────────────────────

(deftest a-deploy-did-that-differs-from-the-jsonld-is-a-violation
  (let [broken (assoc-in fixture [:deploy :deployment/actor-did] "did:web:other.example.com")]
    (is (contains? (rules-of (c/check broken)) :did/deploy-jsonld-match))))

(deftest a-declared-cell-without-a-file-is-a-violation
  (let [broken (update-in fixture [:manifest :actor/cells] conj
                          {:cell/id "ghost_cell" :cell/kind :langgraph :cell/runtime :wasm})]
    (is (contains? (rules-of (c/check broken)) :cells/declared-without-file))))

(deftest a-cell-file-the-manifest-never-declares-is-a-violation
  (let [broken (update fixture :cells conj
                       {:cell/id "stowaway" :cell/kind :langgraph :cell/runtime :wasm
                        :cell/state-graph {:nodes [:x] :entry :x :edges [[:x :end]]}})]
    (is (contains? (rules-of (c/check broken)) :cells/file-without-declaration))))

(deftest a-cell-runtime-that-drifts-from-the-manifest-is-a-violation
  (let [broken (assoc-in fixture [:cells 0 :cell/runtime] :jvm)]
    (is (contains? (rules-of (c/check broken)) :cells/kind-runtime-drift))))

(deftest an-allowlist-wider-than-the-cells-is-a-violation
  (let [broken (update-in fixture [:deploy :deployment/capability-allowlist]
                          conj :yoro-supply.actuate)]
    (is (contains? (rules-of (c/check broken)) :capability/allowlist-beyond-cells))))

(deftest a-cell-missing-from-the-allowlist-is-a-violation
  (let [broken (assoc-in fixture [:deploy :deployment/capability-allowlist]
                         [:yoro-supply.supplier-selection])]
    (is (contains? (rules-of (c/check broken)) :capability/cell-beyond-allowlist))))

(deftest a-lexicon-outside-the-namespace-is-a-violation
  (let [broken (assoc-in fixture [:lexicons 0 :id] "com.etzhayyim.supply.supplierSelection")]
    (is (contains? (rules-of (c/check broken)) :lex/wrong-namespace))))

(deftest a-declared-lex-without-a-lexicon-is-a-violation
  (let [broken (update-in fixture [:manifest :actor/lex] conj {:lex/id "settlement"})]
    (is (contains? (rules-of (c/check broken)) :lex/declared-without-lexicon))))

(deftest a-cell-handler-missing-from-the-agent-is-a-violation
  (let [broken (assoc-in fixture [:cells 0 :cell/handler] "handle_supplier_pick")]
    (is (contains? (rules-of (c/check broken)) :handlers/missing-defn))))

(deftest an-edge-that-names-a-ghost-node-is-a-violation
  (testing ":nodes だけ rename して :edges を忘れる形 —— ghost 参照と unreachable が同時に出る"
    (let [broken (assoc-in fixture [:cells 0 :cell/state-graph :nodes] [:a :b2])
          rules  (rules-of (c/check broken))]
      (is (contains? rules :graph/edge-names-a-ghost))
      (is (contains? rules :graph/node-unreachable)))))

(deftest a-graph-that-never-reaches-end-is-a-violation
  (let [broken (assoc-in fixture [:cells 1 :cell/state-graph :edges] [])]
    (is (contains? (rules-of (c/check broken)) :graph/end-unreachable))))

(deftest an-entry-outside-the-nodes-is-a-violation
  (let [broken (assoc-in fixture [:cells 1 :cell/state-graph :entry] :nowhere)]
    (is (contains? (rules-of (c/check broken)) :graph/entry-not-a-node))))

(deftest a-gate-the-manifest-does-not-carry-is-a-violation
  (let [broken (assoc-in fixture [:cells 0 :cell/gates] ["G99"])]
    (is (contains? (rules-of (c/check broken)) :gates/unknown-gate))))

(deftest an-llm-endpoint-off-localhost-is-a-violation
  (let [broken (assoc-in fixture [:cells 0 :cell/llm :endpoint] "api.example.com:443")]
    (is (contains? (rules-of (c/check broken)) :llm/not-murakumo-only))))

(deftest an-llm-provider-other-than-murakumo-is-a-violation
  (let [broken (assoc-in fixture [:cells 0 :cell/llm :provider] :openai)]
    (is (contains? (rules-of (c/check broken)) :llm/not-murakumo-only))))

(deftest a-deploy-without-approval-required-is-a-violation
  (let [broken (assoc-in fixture [:deploy :deployment/approval-required] false)]
    (is (contains? (rules-of (c/check broken)) :approval/deploy-must-require-approval))))
