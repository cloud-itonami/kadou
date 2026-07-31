(ns kadou.store
  "SSoT for the kadou (稼働) automatic work-time capture actor. Store is a
  protocol injected into the `kadou.actor` StateGraph — `MemStore` is the
  default, deterministic, zero-dep backend (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4313's payroll.store; the activity-domain records
  (observations, sessions, attributions, timesheet entries) use
  `kotoba.activity`'s record shapes verbatim — this actor CONSUMES
  kotoba-lang/activity, it does not reinvent segmentation or rounding.

  Domain:

    worker      — a registered worker (:worker/id, :worker/name). Capture
                  is self-observation: a worker's samples may only be
                  submitted by that worker.
    consent     — a time-bounded, scope-bounded, revocable grant to
                  capture. The ONLY thing that makes an observation
                  admissible. Without it this actor is surveillance, so
                  the governor treats its absence as a hard hold rather
                  than something a human approver can wave through.
    project     — a declared project. Attribution may only name one of
                  these; the advisor cannot invent a client.
    observation — a `kotoba.activity/observation`, per worker,
                  append-only. The ONLY admissible basis for any claim
                  about how long something took (no invented time).
    rule        — a `kotoba.activity/rule`, per worker. The worker's own
                  declaration of what their apps mean, not a policy
                  imposed on them.
    record      — a committed operating record — written ONLY via
                  commit-record!.
    ledger      — append-only audit trail of every proposal/verdict/
                  disposition, commit or hold."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (worker [s worker-id])
  (consent-of [s worker-id])
  (projects [s])
  (rules-of [s worker-id])
  (observations-of [s worker-id])
  (records-of [s worker-id])
  (ledger [s])
  (register-worker! [s w])
  (grant-consent! [s consent])
  (revoke-consent! [s worker-id at])
  (register-project! [s p])
  (register-rule! [s worker-id rule])
  (record-observations! [s worker-id observations])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (worker [_ worker-id] (get-in @a [:workers worker-id]))
  (consent-of [_ worker-id] (get-in @a [:consents worker-id]))
  (projects [_] (vals (:projects @a)))
  (rules-of [_ worker-id] (get-in @a [:rules worker-id] []))
  (observations-of [_ worker-id] (get-in @a [:observations worker-id] []))
  (records-of [_ worker-id] (filter #(= worker-id (:worker-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-worker! [s w]
    (swap! a assoc-in [:workers (:worker/id w)] w) s)
  (grant-consent! [s consent]
    (swap! a assoc-in [:consents (:consent/worker consent)] consent) s)
  (revoke-consent! [s worker-id at]
    ;; Only revoke a consent that exists. `update-in ... assoc` would
    ;; CREATE `{:consent/revoked-at at}` for an unknown worker — a record
    ;; with a revocation and no grant, which `consent-covers?` then has to
    ;; reason about. Found by the MemStore ≡ DatomicStore contract test,
    ;; which is what that test is for.
    (when (get-in @a [:consents worker-id])
      (swap! a update-in [:consents worker-id] assoc :consent/revoked-at at))
    s)
  (register-project! [s p]
    (swap! a assoc-in [:projects (:project/id p)] p) s)
  (register-rule! [s worker-id rule]
    (swap! a update-in [:rules worker-id] (fnil conj []) rule) s)
  (record-observations! [s worker-id observations]
    (swap! a update-in [:observations worker-id] (fnil into []) observations) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:workers {} :consents {} :projects {}
                                    :rules {} :observations {}
                                    :records [] :ledger []}
                                   seed)))))

;; ---------------------------------------------------------------------------
;; Consent
;; ---------------------------------------------------------------------------

(defn consent
  "Construct a capture consent. `from`/`until` are epoch milliseconds and
  `scopes` the `kotoba.activity/sources` the worker agreed to have
  sampled — consenting to :app is not consenting to :window, because the
  window title is the part that says what you were reading."
  [worker-id from until scopes]
  {:consent/worker     worker-id
   :consent/from       from
   :consent/until      until
   :consent/scopes     (set scopes)
   :consent/revoked-at nil})

(defn consent-covers?
  "Does `c` admit capture over `[from to]` for `scopes`? A revocation at
  time R invalidates everything at or after R — revoking consent stops
  future capture, and time already observed and already attributed stays
  in the ledger rather than being silently rewritten."
  [c [from to] scopes]
  (boolean
   (and c
        (number? from) (number? to)
        ;; A consent missing its own bounds does not cover anything. Not a
        ;; crash and not a pass: a malformed grant is exactly the case
        ;; where failing open would be worst.
        (number? (:consent/from c)) (number? (:consent/until c))
        (set? (:consent/scopes c))
        (>= from (:consent/from c))
        (<= to (:consent/until c))
        (or (nil? (:consent/revoked-at c)) (< to (:consent/revoked-at c)))
        (every? (:consent/scopes c) scopes))))

;; ---------------------------------------------------------------------------
;; DatomicStore (langchain.db)
;;
;; The same protocol over a Datomic-API-compatible EAV store, so the
;; backend is a swap and not a rewrite — the seam the fleet's other actors
;; use (cloud-itonami-isic-6511's underwriting.store is the reference
;; adopter). Pure `.cljc`: it runs offline against langchain.db's
;; in-process DataScript, and the SAME record points at a real Datomic or
;; a kotoba-server pod by swapping langchain.db's `:db-api` (see
;; langchain.kotoba-db).
;;
;; `MemStore` and `DatomicStore` pass the same contract test, which is the
;; point: the actor, the KadouGovernor and the audit ledger never know
;; which SSoT they run on. In particular the consent check is backend-
;; independent — a store that could not answer `consent-of` correctly
;; would turn the one hold that has no escalation path into a coin flip.
;;
;; Compound values are EDN string blobs (`langchain-store.core/enc`) so
;; langchain.db does not expand them into sub-entities. Streams are
;; seq-keyed: observations and rules are per-worker, the ledger and
;; records are global, and all four are append-only on every backend.
;; ---------------------------------------------------------------------------

(def ^:private schema
  (ls/identity-schema [:worker/id :consent/worker :project/id
                       :obs/key :rule/key :record/seq :ledger/seq]))

(defn- stream
  "Read a per-worker seq-keyed blob stream."
  [conn worker-attr seq-attr edn-attr worker-id]
  (->> (d/q [:find '?s '?v :in '$ '?w
             :where ['?e worker-attr '?w] ['?e seq-attr '?s] ['?e edn-attr '?v]]
            (d/db conn) worker-id)
       (sort-by first)
       (mapv (comp ls/dec* second))))

(defn- next-seq [conn seq-attr]
  (count (d/q [:find '?e :where ['?e seq-attr '_]] (d/db conn))))

(defrecord DatomicStore [conn]
  Store
  (worker [_ worker-id]
    (ls/dec* (d/q '[:find ?v . :in $ ?id
                    :where [?e :worker/id ?id] [?e :worker/edn ?v]]
                  (d/db conn) worker-id)))
  (consent-of [_ worker-id]
    (ls/dec* (d/q '[:find ?v . :in $ ?id
                    :where [?e :consent/worker ?id] [?e :consent/edn ?v]]
                  (d/db conn) worker-id)))
  (projects [_]
    (->> (d/q '[:find [?v ...] :where [?e :project/id _] [?e :project/edn ?v]] (d/db conn))
         (map ls/dec*)
         (sort-by :project/id)
         vec))
  (rules-of [_ worker-id] (stream conn :rule/worker :rule/seq :rule/edn worker-id))
  (observations-of [_ worker-id] (stream conn :obs/worker :obs/seq :obs/edn worker-id))
  (records-of [_ worker-id]
    (->> (ls/read-stream conn :record/seq :record/edn)
         (filter #(= worker-id (:worker-id %)))
         vec))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (register-worker! [s w]
    (d/transact! conn [{:worker/id (:worker/id w) :worker/edn (ls/enc w)}]) s)
  (grant-consent! [s c]
    (d/transact! conn [{:consent/worker (:consent/worker c) :consent/edn (ls/enc c)}]) s)
  (revoke-consent! [s worker-id at]
    (when-let [c (consent-of s worker-id)]
      (d/transact! conn [{:consent/worker worker-id
                          :consent/edn (ls/enc (assoc c :consent/revoked-at at))}]))
    s)
  (register-project! [s p]
    (d/transact! conn [{:project/id (:project/id p) :project/edn (ls/enc p)}]) s)
  (register-rule! [s worker-id rule]
    (let [n (count (rules-of s worker-id))]
      (d/transact! conn [{:rule/key (str worker-id "#" n) :rule/worker worker-id
                          :rule/seq n :rule/edn (ls/enc rule)}]))
    s)
  (record-observations! [s worker-id observations]
    (let [n (count (observations-of s worker-id))]
      (d/transact! conn (vec (map-indexed
                              (fn [i o]
                                {:obs/key (str worker-id "#" (+ n i))
                                 :obs/worker worker-id :obs/seq (+ n i)
                                 :obs/edn (ls/enc o)})
                              observations))))
    s)
  (commit-record! [s record]
    (ls/append-blob! conn :record/seq :record/edn (next-seq conn :record/seq) record) s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (next-seq conn :ledger/seq) fact) s))

(defn datomic-store
  "A DatomicStore over a fresh in-process langchain.db connection. Point
  it at a real Datomic or a kotoba-server pod by swapping langchain.db's
  `:db-api`; nothing above this line changes."
  []
  (->DatomicStore (d/create-conn schema)))
