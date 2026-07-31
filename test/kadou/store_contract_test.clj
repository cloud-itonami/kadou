(ns kadou.store-contract-test
  "MemStore ≡ DatomicStore.

  Every assertion runs against BOTH backends. That is the whole point of
  the protocol seam: the actor, the KadouGovernor and the audit ledger
  never know which SSoT they run on, and a backend that answered
  `consent-of` differently would turn the one hold with no escalation
  path into a coin flip.

  This suite has already earned its keep: it caught `MemStore`'s
  `revoke-consent!` fabricating a consent record for a worker who never
  had one, which the DatomicStore correctly declined to do."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.activity :as activity]
            [kadou.store :as store]
            [kadou.actor :as actor]))

(def ^:private min-ms 60000)
(def ^:private t0 1767225600000)
(defn- at [m] (+ t0 (* m min-ms)))

(defn- poll [from to subject detail]
  (mapv #(activity/observation (at %) :app subject :detail detail)
        (range from (inc to))))

(def ^:private morning (poll 0 100 "Emacs" "activity.cljc"))

(def backends
  "Both constructors, run through the identical suite below."
  {:mem     store/mem-store
   :datomic store/datomic-store})

(defn- seeded [make]
  (let [st (make)]
    (store/register-worker! st {:worker/id "w-1" :worker/name "Rin"})
    (store/grant-consent! st (store/consent "w-1" (at -1440) (at 1440) #{:app}))
    (store/register-project! st {:project/id "kotoba-activity" :project/name "activity lib"})
    (store/register-rule! st "w-1" (activity/rule :emacs "kotoba-activity"
                                                  :source :app :subject-is "Emacs"
                                                  :detail-contains "activity" :weight 0.9))
    st))

(defn- each-backend
  "Run `f` against a freshly seeded store of every backend. A function
  rather than a macro so static analysis can see the binding."
  [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (seeded make)))))

(defn- each-empty-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (make)))))

;; ---------------------------------------------------------------------------
;; Directories
;; ---------------------------------------------------------------------------

(deftest a-registered-worker-reads-back
  (each-backend
   (fn [st]
     (is (= {:worker/id "w-1" :worker/name "Rin"} (store/worker st "w-1")))
     (is (nil? (store/worker st "nobody"))))))

(deftest registering-the-same-id-twice-upserts-rather-than-forking
  (each-backend
   (fn [st]
     (store/register-worker! st {:worker/id "w-1" :worker/name "Rin (renamed)"})
     (is (= "Rin (renamed)" (:worker/name (store/worker st "w-1")))))))

(deftest projects-read-back-as-a-set
  (each-backend
   (fn [st]
     (store/register-project! st {:project/id "beta"})
     (is (= #{"kotoba-activity" "beta"} (set (map :project/id (store/projects st))))))))

;; ---------------------------------------------------------------------------
;; Consent — the backend-independent hold
;; ---------------------------------------------------------------------------

(deftest consent-reads-back-whole
  (each-backend
   (fn [st]
     (let [c (store/consent-of st "w-1")]
       (is (= #{:app} (:consent/scopes c)))
       (is (= (at -1440) (:consent/from c)))
       (is (nil? (:consent/revoked-at c)))))))

(deftest revocation-is-visible-on-both-backends
  (each-backend
   (fn [st]
     (store/revoke-consent! st "w-1" (at 50))
     (is (= (at 50) (:consent/revoked-at (store/consent-of st "w-1"))))
     (testing "and it changes what consent-covers? answers"
       (is (not (store/consent-covers? (store/consent-of st "w-1")
                                       [(at 0) (at 100)] #{:app})))))))

(deftest revocation-preserves-the-scopes-it-was-granted-with
  (each-backend
   (fn [st]
     (store/revoke-consent! st "w-1" (at 50))
     (is (= #{:app} (:consent/scopes (store/consent-of st "w-1")))))))

(deftest revoking-a-consent-that-does-not-exist-is-a-no-op
  (testing "otherwise the store holds a revocation with no grant behind it"
    (each-backend
     (fn [st]
       (store/revoke-consent! st "w-2" (at 50))
       (is (nil? (store/consent-of st "w-2")))))))

(deftest a-malformed-consent-does-not-cover-and-does-not-crash
  (testing "a grant with no bounds is the worst case to fail open on"
    (is (not (store/consent-covers? {:consent/revoked-at 1} [0 100] #{:app})))
    (is (not (store/consent-covers? {:consent/from 0 :consent/until 100} [0 50] #{:app})))
    (is (not (store/consent-covers? nil [0 100] #{:app})))))

;; ---------------------------------------------------------------------------
;; Append-only streams
;; ---------------------------------------------------------------------------

(deftest observations-append-and-keep-their-order
  (each-backend
   (fn [st]
     (store/record-observations! st "w-1" (subvec morning 0 10))
     (store/record-observations! st "w-1" (subvec morning 10))
     (let [obs (store/observations-of st "w-1")]
       (is (= 101 (count obs)))
       (is (= (mapv :obs/at morning) (mapv :obs/at obs)))
       (testing "and they segment identically, which is what the governor recomputes"
         (is (= (activity/segment morning) (activity/segment obs))))))))

(deftest observations-are-scoped-to-their-worker
  (each-backend
   (fn [st]
     (store/register-worker! st {:worker/id "w-2"})
     (store/record-observations! st "w-1" (subvec morning 0 5))
     (store/record-observations! st "w-2" (subvec morning 5 8))
     (is (= 5 (count (store/observations-of st "w-1"))))
     (is (= 3 (count (store/observations-of st "w-2")))))))

(deftest rules-append-per-worker
  (each-backend
   (fn [st]
     (store/register-rule! st "w-1" (activity/rule :slack "internal" :subject-is "Slack"))
     (is (= [:emacs :slack] (mapv :rule/id (store/rules-of st "w-1"))))
     (is (empty? (store/rules-of st "w-2"))))))

(deftest the-ledger-is-append-only-and-ordered
  (each-backend
   (fn [st]
     (store/append-ledger! st {:disposition :hold :n 1})
     (store/append-ledger! st {:disposition :commit :n 2})
     (store/append-ledger! st {:disposition :hold :n 3})
     (is (= [1 2 3] (mapv :n (store/ledger st)))))))

(deftest records-are-filtered-by-worker
  (each-backend
   (fn [st]
     (store/commit-record! st {:worker-id "w-1" :op :attribute-session})
     (store/commit-record! st {:worker-id "w-2" :op :attribute-session})
     (is (= 1 (count (store/records-of st "w-1"))))
     (is (= 1 (count (store/records-of st "w-2")))))))

(deftest an-empty-store-answers-empty-not-nil
  (each-empty-backend
   (fn [st]
     (is (empty? (store/observations-of st "w-1")))
     (is (empty? (store/rules-of st "w-1")))
     (is (empty? (store/ledger st)))
     (is (empty? (store/records-of st "w-1")))
     (is (empty? (store/projects st))))))

;; ---------------------------------------------------------------------------
;; The actor runs unchanged on either backend
;; ---------------------------------------------------------------------------

(deftest the-graph-produces-the-same-disposition-on-both-backends
  (let [run (fn [make]
              (let [st (seeded make)
                    g (actor/build-graph {:store st})
                    ingest (actor/run-request! g {:worker-id "w-1" :op :record-observations
                                                  :observations morning}
                                               {} "t-1")
                    session (first (activity/segment morning))
                    attr (actor/run-request! g {:worker-id "w-1" :op :attribute-session
                                                :session/start (:session/start session)}
                                             {} "t-2")]
                {:ingest (get-in ingest [:state :disposition])
                 :attr   (get-in attr [:state :disposition])
                 :project (get-in attr [:state :proposal :project])
                 :duration (get-in attr [:state :proposal :duration-ms])
                 :ledger (mapv :disposition (store/ledger st))
                 :observations (count (store/observations-of st "w-1"))}))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (testing "and that shared answer is the right one"
      (is (= {:ingest :commit :attr :commit :project "kotoba-activity"
              :duration (* 100 min-ms) :ledger [:commit :commit] :observations 101}
             (run store/datomic-store))))))

(deftest an-unconsented-ingest-is-held-on-both-backends
  (let [run (fn [make]
              (let [st (seeded make)
                    _ (store/revoke-consent! st "w-1" (at -1))
                    g (actor/build-graph {:store st})
                    r (actor/run-request! g {:worker-id "w-1" :op :record-observations
                                             :observations morning}
                                          {} "t-1")]
                {:disposition (get-in r [:state :disposition])
                 :written (count (store/observations-of st "w-1"))
                 :rules (mapv :rule (get-in r [:state :verdict :violations]))}))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (is (= {:disposition :hold :written 0 :rules [:no-consent]}
           (run store/datomic-store)))))
