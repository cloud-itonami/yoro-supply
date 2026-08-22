(ns etzhayyim.yoro-supply.contract
  "yoro-supply actor descriptor の適合規則。**純粋** —— ファイルも network も読まない。

   入力は parse 済みのデータ:

     manifest    manifest.edn 由来（gen-3、canonical）。EDN なのでキーワードキー
     jsonld      manifest.jsonld 由来（gen-1 legacy）。キーは**文字列のまま**扱う
                 （keywordize すると \"@context\" が壊れるので境界で変換しない ——
                 airshed / cargo の descriptor 規約と同じ）
     deploy      deploy/app-aozora.edn 由来。EDN なのでキーワードキー
     cells       cells/*.edn 由来の vector
     lexicons    lex/*.edn 由来の vector（AT Protocol lexicon 形）
     defn-names  py/agent.cljc の source から抽出した defn 名の set（文字列）

   出力は違反の vector。空なら適合。

   ## この repo の契約は 5 つのファイル面の一致でできている

   manifest.edn は :actor/cells と :actor/lex を宣言し、cells/*.edn は
   :cell/handler で py/agent.cljc の関数を名指しし、deploy/app-aozora.edn は
   :deployment/capability-allowlist で cell 由来の能力を配る。この 5 面の
   どれか 1 つだけを書き換えるのが一番起こりやすい壊れ方（cargo が 2026-05〜08 に
   踏んだ『descriptor が静かに割れる』形）だが、2026-08-22 までこの一致を検査する
   ものは何も無かった。規則を純粋関数として書き、fixture で「規則が実際に落ちる」
   ことを見せてから実ファイルに当てる。

   manifest.jsonld の lexicon namespace（com.etzhayyim.supply.*）は lex/*.edn
   （com.etzhayyim.yorosupply.*）と既に drift しているが、manifest.edn 自身が
   :actor/legacy で jsonld を非正本と宣言しているので、ここでは jsonld からは
   **DID だけ**を契約に数える —— DID は世代を跨いで割れてはならない唯一の identity。"
  (:require [clojure.string :as str]))

(defn- v [rule detail] {:rule rule :detail detail})

;; ── 取り出し・変換 ──────────────────────────────────────────────────────────

(defn cell-capability
  "cell id → deploy allowlist が使う能力名（文字列）。
   supplier_selection → yoro-supply.supplier-selection"
  [cell-id]
  (str "yoro-supply." (str/replace cell-id "_" "-")))

(defn handler-fn-name
  "cell の :cell/handler（underscore 形）→ agent の defn 名（hyphen 形）。"
  [handler]
  (str/replace handler "_" "-"))

(defn manifest-cell-ids [manifest]
  (set (map :cell/id (:actor/cells manifest))))

(defn manifest-gate-ids [manifest]
  (set (map :gate/id (:actor/gates manifest))))

(defn manifest-lex-ids [manifest]
  (set (map :lex/id (:actor/lex manifest))))

(def lex-prefix "com.etzhayyim.yorosupply.")

(defn lexicon-short-ids
  "lex/*.edn の :id から prefix を落とした短名の set。prefix を持たない id は
   そのまま返す（check-lex が prefix 違反として報告する）。"
  [lexicons]
  (set (map (fn [l]
              (let [id (:id l)]
                (if (str/starts-with? (str id) lex-prefix)
                  (subs id (count lex-prefix))
                  id)))
            lexicons)))

;; ── 規則 ────────────────────────────────────────────────────────────────────

(defn check-did
  "DID は世代（gen-1 jsonld / gen-3 deploy）を跨いで割れてはならない。
   cargo が実際にやった改名半端（@id と did.json が別 DID）と同じ入口。"
  [jsonld deploy]
  (let [jid (get jsonld "id")
        did (:deployment/actor-did deploy)]
    (when (not= jid did)
      [(v :did/deploy-jsonld-match
          (str "jsonld id " (pr-str jid) " ≠ deploy actor-did " (pr-str did)))])))

(defn check-cells
  "manifest :actor/cells と cells/*.edn の全単射 + kind/runtime の一致。
   manifest だけに cell を足すと配備が実体の無い cell を数え、cells/ だけに
   足すとその cell は manifest 上存在しないまま動く —— 両方向 violation。"
  [manifest cells]
  (let [declared (manifest-cell-ids manifest)
        actual   (set (map :cell/id cells))
        by-id    (into {} (map (juxt :cell/id identity) (:actor/cells manifest)))]
    (concat
     (for [c (sort (remove actual declared))]
       (v :cells/declared-without-file
          (str "manifest declares cell " c " but cells/ ships no descriptor")))
     (for [c (sort (remove declared actual))]
       (v :cells/file-without-declaration
          (str "cells/ ships " c " which the manifest never declares")))
     (for [c cells
           :let [m (get by-id (:cell/id c))]
           :when m
           :let [drift (cond
                         (not= (:cell/kind m) (:cell/kind c))
                         (str ":cell/kind " (pr-str (:cell/kind c)) " ≠ manifest " (pr-str (:cell/kind m)))
                         (not= (:cell/runtime m) (:cell/runtime c))
                         (str ":cell/runtime " (pr-str (:cell/runtime c)) " ≠ manifest " (pr-str (:cell/runtime m))))]
           :when drift]
       (v :cells/kind-runtime-drift (str (:cell/id c) ": " drift))))))

(defn check-allowlist
  "deploy の :deployment/capability-allowlist は manifest の cell 集合から機械的に
   導出される（yoro-supply.<kebab cell-id>）。広ければ『宣言していない権限を
   配る』、狭ければ『宣言した cell が配備で黙って死ぬ』—— 両方向 violation。"
  [manifest deploy]
  (let [expect (set (map cell-capability (manifest-cell-ids manifest)))
        allow  (set (map name (:deployment/capability-allowlist deploy)))]
    (concat
     (for [c (sort (remove expect allow))]
       (v :capability/allowlist-beyond-cells
          (str "deploy allowlist has " c " which maps to no declared cell")))
     (for [c (sort (remove allow expect))]
       (v :capability/cell-beyond-allowlist
          (str c " is derived from a declared cell but the allowlist does not carry it"))))))

(defn check-lex
  "manifest :actor/lex と lex/*.edn の全単射。lexicon の :id は必ず
   com.etzhayyim.yorosupply. prefix を持つ。"
  [manifest lexicons]
  (let [declared (manifest-lex-ids manifest)
        shipped  (lexicon-short-ids lexicons)]
    (concat
     (for [l lexicons
           :when (not (str/starts-with? (str (:id l)) lex-prefix))]
       (v :lex/wrong-namespace
          (str (pr-str (:id l)) " does not live under " lex-prefix)))
     (for [c (sort (remove shipped declared))]
       (v :lex/declared-without-lexicon
          (str "manifest declares lex " c " but lex/ ships no such lexicon")))
     (for [c (sort (remove declared shipped))]
       (v :lex/lexicon-not-declared
          (str "lex/ ships " c " which the manifest never declares"))))))

(defn check-handlers
  "各 cell の :cell/handler は py/agent.cljc に実在する defn を指す。
   handler を cell 側だけ改名すると、descriptor は valid EDN のまま配備が
   実行時に初めて落ちる —— ここで捕まえる。"
  [cells defn-names]
  (for [c cells
        :let [h (:cell/handler c)
              f (when h (handler-fn-name h))]
        :when (and f (not (contains? defn-names f)))]
    (v :handlers/missing-defn
       (str "cell " (:cell/id c) " names handler " (pr-str h)
            " but the agent defines no " (pr-str f)))))

(defn check-state-graph
  "各 cell の :cell/state-graph の well-formedness:
   (1) :entry ∈ :nodes
   (2) 全 edge の両端 ∈ :nodes ∪ #{:end}
   (3) :end が :entry から到達可能（終わらない graph は配備で hang する）
   (4) 全 node が :entry から到達可能（unreachable node は live に見える dead code）
   :nodes だけ rename して :edges を忘れるのが一番起こりやすい壊れ方で、
   そのとき (2) と (4) が同時に落ちる。"
  [cells]
  (mapcat
   (fn [c]
     (let [{:keys [nodes entry edges]} (:cell/state-graph c)
           node-set (set nodes)
           ok?      (fn [n] (or (contains? node-set n) (= :end n)))
           adj      (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} edges)
           reach    (loop [seen #{entry} frontier [entry]]
                      (if (empty? frontier)
                        seen
                        (let [nxt (remove seen (mapcat adj frontier))]
                          (recur (into seen nxt) (vec nxt)))))]
       (concat
        (when-not (contains? node-set entry)
          [(v :graph/entry-not-a-node
              (str (:cell/id c) ": entry " (pr-str entry) " is not in :nodes"))])
        (for [[a b] edges
              :when (or (not (contains? node-set a)) (not (ok? b)))]
          (v :graph/edge-names-a-ghost
             (str (:cell/id c) ": edge " (pr-str [a b]) " references a node that does not exist")))
        (when-not (contains? reach :end)
          [(v :graph/end-unreachable
              (str (:cell/id c) ": :end is unreachable from " (pr-str entry)))])
        (for [n (sort-by str (remove reach node-set))]
          (v :graph/node-unreachable
             (str (:cell/id c) ": node " (pr-str n) " is unreachable from " (pr-str entry)))))))
   cells))

(defn check-gates
  "cell が cite する gate id は manifest の gate 表に実在する。"
  [manifest cells]
  (let [known (manifest-gate-ids manifest)]
    (for [c cells
          g (:cell/gates c)
          :when (not (contains? known g))]
      (v :gates/unknown-gate
         (str "cell " (:cell/id c) " cites gate " (pr-str g)
              " which the manifest gate table does not carry")))))

(defn check-llm
  "G5 murakumo-only の機械可読な面: :cell/llm を持つ cell は provider :murakumo
   かつ endpoint 127.0.0.1:4000。endpoint を外部ホストに向ける 1 行の編集で
   『LLM access is Murakumo-only』という README・manifest 両方の宣言が嘘になる。"
  [cells]
  (for [c cells
        :let [llm (:cell/llm c)]
        :when llm
        :let [drift (cond
                      (not= :murakumo (:provider llm))
                      (str "provider " (pr-str (:provider llm)))
                      (not= "127.0.0.1:4000" (:endpoint llm))
                      (str "endpoint " (pr-str (:endpoint llm))))]
        :when drift]
    (v :llm/not-murakumo-only
       (str "cell " (:cell/id c) " " drift " — G5 requires KotobaLLM 127.0.0.1:4000"))))

(defn check-approval
  "deploy は :deployment/approval-required true を保つ（G1 consent-bound の配備面）。"
  [deploy]
  (when-not (true? (:deployment/approval-required deploy))
    [(v :approval/deploy-must-require-approval
        ":deployment/approval-required is not true")]))

(defn check
  "全規則。空 vector なら適合。"
  [{:keys [manifest jsonld deploy cells lexicons defn-names]}]
  (vec (concat (check-did jsonld deploy)
               (check-cells manifest cells)
               (check-allowlist manifest deploy)
               (check-lex manifest lexicons)
               (check-handlers cells defn-names)
               (check-state-graph cells)
               (check-gates manifest cells)
               (check-llm cells)
               (check-approval deploy))))
