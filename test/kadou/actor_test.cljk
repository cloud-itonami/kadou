(ns kadou.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.activity :as activity]
            [kadou.store :as store]
            [kadou.actor :as actor]))

(def ^:private min-ms 60000)
(def ^:private t0 1767225600000) ;; 2026-01-01T00:00:00Z

(defn- at [minutes] (+ t0 (* minutes min-ms)))

(defn- poll [from-min to-min subject detail]
  (mapv #(activity/observation (at %) :app subject :detail detail)
        (range from-min (inc to-min))))

(def ^:private morning (poll 0 100 "Emacs" "activity.cljc"))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-worker! st {:worker/id "w-1" :worker/name "Rin"})
    (store/grant-consent! st (store/consent "w-1" (at -1440) (at 1440) #{:app}))
    (store/register-project! st {:project/id "kotoba-activity"})
    (store/register-rule! st "w-1" (activity/rule :emacs "kotoba-activity"
                                                  :source :app :subject-is "Emacs"
                                                  :detail-contains "activity" :weight 0.9))
    st))

(defn- disposition [result] (get-in result [:state :disposition]))
(defn- proposal [result] (get-in result [:state :proposal]))

;; ---------------------------------------------------------------------------
;; Ingestion goes through the governor like any other op
;; ---------------------------------------------------------------------------

(deftest consented-ingestion-commits-and-lands-observations
  (let [st (fresh-store)
        g  (actor/build-graph {:store st})
        r  (actor/run-request! g {:worker-id "w-1" :op :record-observations
                                  :observations morning}
                               {} "t-ingest")]
    (is (= :done (:status r)))
    (is (= :commit (disposition r)))
    (is (= 101 (count (store/observations-of st "w-1"))))
    (is (= [:commit] (mapv :disposition (store/ledger st))))))

(deftest unconsented-ingestion-never-reaches-the-store
  (let [st (fresh-store)
        _  (store/revoke-consent! st "w-1" (at -1))
        g  (actor/build-graph {:store st})
        r  (actor/run-request! g {:worker-id "w-1" :op :record-observations
                                  :observations morning}
                               {} "t-nope")]
    (is (= :hold (disposition r)))
    (testing "held observations are not written — a hold is not a warning"
      (is (empty? (store/observations-of st "w-1"))))
    (is (= [:hold] (mapv :disposition (store/ledger st))))
    (is (some #(= :no-consent (:rule %)) (get-in r [:state :verdict :violations])))))

;; ---------------------------------------------------------------------------
;; Attribution
;; ---------------------------------------------------------------------------

(defn- ingested-store []
  (doto (fresh-store) (store/record-observations! "w-1" morning)))

(deftest attribution-commits-when-the-workers-own-rule-matches
  (let [st (ingested-store)
        g  (actor/build-graph {:store st})
        session (first (activity/segment morning))
        r  (actor/run-request! g {:worker-id "w-1" :op :attribute-session
                                  :session/start (:session/start session)}
                               {} "t-attr")]
    (is (= :done (:status r)))
    (is (= :commit (disposition r)))
    (is (= "kotoba-activity" (:project (proposal r))))
    (is (= (:session/duration-ms session) (:duration-ms (proposal r))))
    (is (= 1 (count (store/records-of st "w-1"))))))

(deftest an-unmatched-session-escalates-rather-than-guessing
  (let [st (fresh-store)
        unmatched (poll 0 100 "Safari" "holiday deals")
        _ (store/record-observations! st "w-1" unmatched)
        g (actor/build-graph {:store st})
        session (first (activity/segment unmatched))
        r (actor/run-request! g {:worker-id "w-1" :op :attribute-session
                                 :session/start (:session/start session)}
                              {} "t-unmatched")]
    (testing "no rule fires, so confidence is 0.0 and the run interrupts for a human"
      (is (= :interrupted (:status r)))
      (is (nil? (:project (proposal r))))
      (is (zero? (:confidence (proposal r)))))
    (testing "nothing is committed while the thread is interrupted"
      (is (empty? (store/records-of st "w-1"))))))

(deftest approval-resumes-the-interrupted-thread-to-commit
  (let [st (fresh-store)
        unmatched (poll 0 100 "Safari" "holiday deals")
        _ (store/record-observations! st "w-1" unmatched)
        g (actor/build-graph {:store st})
        session (first (activity/segment unmatched))
        interrupted (actor/run-request! g {:worker-id "w-1" :op :attribute-session
                                           :session/start (:session/start session)}
                                        {} "t-approve")]
    (is (= :interrupted (:status interrupted)))
    (let [resumed (actor/approve! g "t-approve")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "w-1"))))
      (is (= [:commit] (mapv :disposition (store/ledger st)))))))

;; ---------------------------------------------------------------------------
;; Timesheet submission
;; ---------------------------------------------------------------------------

(deftest timesheet-submission-always-waits-for-a-human
  (let [st (ingested-store)
        g  (actor/build-graph {:store st})
        r  (actor/run-request! g {:worker-id "w-1" :op :submit-timesheet
                                  :period-from (at 0) :period-to (at 100)
                                  :date-of (constantly "2026-01-01")}
                               {} "t-submit")]
    (is (= :interrupted (:status r)))
    (testing "the proposed entries are floor-rounded from observed time only"
      (let [entries (:entries (proposal r))]
        (is (= 1 (count entries)))
        (is (= 1.5 (:ts/hours (first entries))))   ;; 100 observed minutes floor to 90
        (is (= "kotoba-activity" (:ts/project (first entries))))))
    (testing "coverage travels with the proposal instead of being rounded away"
      (is (= 1.0 (get-in (proposal r) [:coverage :coverage/ratio]))))))

(deftest partially-attributed-days-report-their-real-coverage
  (let [st (fresh-store)
        _ (store/record-observations! st "w-1" (poll 0 60 "Emacs" "activity.cljc"))
        _ (store/record-observations! st "w-1" (poll 70 130 "Safari" "news"))
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:worker-id "w-1" :op :submit-timesheet
                                 :period-from (at 0) :period-to (at 130)
                                 :date-of (constantly "2026-01-01")}
                              {} "t-partial")
        entries (:entries (proposal r))]
    (is (= 0.5 (get-in (proposal r) [:coverage :coverage/ratio])))
    (testing "the unattributed hour is absent from the entries, not folded in"
      (is (= 1 (count entries)))
      (is (= 1.0 (activity/total-hours entries))))))

;; ---------------------------------------------------------------------------
;; The unconditional invariant
;; ---------------------------------------------------------------------------

(deftest the-advisor-cannot-commit-what-the-governor-refuses
  (testing "every hard hold leaves the store untouched and a ledger entry behind"
    (doseq [[label request]
            [["unregistered worker"
              {:worker-id "ghost" :op :record-observations :observations morning}]
             ["someone else's samples"
              {:worker-id "w-1" :op :record-observations :subject-worker "w-2"
               :observations morning}]]]
      (let [st (fresh-store)
            g  (actor/build-graph {:store st})
            r  (actor/run-request! g request {} (str "t-" (hash label)))]
        (is (= :hold (disposition r)) label)
        (is (empty? (store/records-of st "w-1")) label)
        (is (empty? (store/observations-of st "w-1")) label)
        (is (= [:hold] (mapv :disposition (store/ledger st))) label)))))
