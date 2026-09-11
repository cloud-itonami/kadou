(ns kadou.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.activity :as activity]
            [kadou.store :as store]
            [kadou.governor :as governor]))

(def ^:private min-ms 60000)
(def ^:private t0 1767225600000) ;; 2026-01-01T00:00:00Z

(defn- at [minutes] (+ t0 (* minutes min-ms)))

(defn- poll [from-min to-min subject detail]
  (mapv #(activity/observation (at %) :app subject :detail detail)
        (range from-min (inc to-min))))

;; The worker's own morning: 0–100 min in Emacs on this library.
(def ^:private morning (poll 0 100 "Emacs" "activity.cljc"))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-worker! st {:worker/id "w-1" :worker/name "Rin"})
    (store/register-worker! st {:worker/id "w-2" :worker/name "Kei"})
    (store/grant-consent! st (store/consent "w-1" (at -1440) (at 1440) #{:app :vcs}))
    (store/register-project! st {:project/id "kotoba-activity" :project/name "activity lib"})
    (store/register-rule! st "w-1" (activity/rule :emacs "kotoba-activity"
                                                  :source :app :subject-is "Emacs"
                                                  :detail-contains "activity" :weight 0.9))
    (store/record-observations! st "w-1" morning)
    st))

(def ^:private observed-session
  (first (activity/segment morning)))

(defn- clean-proposal []
  {:op :attribute-session
   :effect :propose
   :session/start (:session/start observed-session)
   :session/end   (:session/end observed-session)
   :duration-ms   (:session/duration-ms observed-session)
   :project "kotoba-activity"
   :confidence 0.9})

(defn- check [request proposal store]
  (governor/check request {} proposal store))

;; ---------------------------------------------------------------------------
;; Baseline
;; ---------------------------------------------------------------------------

(deftest ok-on-clean-observation-backed-attribution
  (let [v (check {:worker-id "w-1"} (clean-proposal) (fresh-store))]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-worker
  (let [v (check {:worker-id "nobody"} (clean-proposal) (fresh-store))]
    (is (:hard? v))
    (is (some #(= :no-worker (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [v (check {:worker-id "w-1"} (assoc (clean-proposal) :effect :direct-write) (fresh-store))]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

;; ---------------------------------------------------------------------------
;; Consent — the line between time capture and surveillance
;; ---------------------------------------------------------------------------

(deftest hard-without-any-consent
  (let [st (store/mem-store)]
    (store/register-worker! st {:worker/id "w-1" :worker/name "Rin"})
    (store/register-project! st {:project/id "kotoba-activity"})
    (store/record-observations! st "w-1" morning)
    (let [v (check {:worker-id "w-1"} (clean-proposal) st)]
      (is (:hard? v))
      (is (some #(= :no-consent (:rule %)) (:violations v))))))

(deftest hard-when-the-span-falls-outside-the-granted-window
  (let [st (fresh-store)]
    (store/grant-consent! st (store/consent "w-1" (at 500) (at 900) #{:app}))
    (let [v (check {:worker-id "w-1"} (clean-proposal) st)]
      (is (:hard? v))
      (is (some #(= :no-consent (:rule %)) (:violations v))))))

(deftest hard-after-consent-is-revoked
  (let [st (fresh-store)]
    (store/revoke-consent! st "w-1" (at 50))
    (let [v (check {:worker-id "w-1"} (clean-proposal) st)]
      (is (:hard? v))
      (is (some #(= :no-consent (:rule %)) (:violations v))))
    (testing "a revocation after the observed span leaves that span admissible"
      (store/revoke-consent! st "w-1" (at 500))
      (is (:ok? (check {:worker-id "w-1"} (clean-proposal) st))))))

(deftest hard-when-ingesting-a-source-outside-the-granted-scopes
  (let [st (fresh-store)
        window-samples (mapv #(activity/observation (at %) :window "Emacs" :detail "salary.md")
                             (range 0 5))
        v (check {:worker-id "w-1"}
                 {:op :record-observations :effect :propose
                  :observations window-samples :confidence 0.9}
                 st)]
    (testing "consent to :app is not consent to :window"
      (is (:hard? v))
      (is (some #(= :no-consent (:rule %)) (:violations v)))))
  (testing "an in-scope source passes"
    (is (:ok? (check {:worker-id "w-1"}
                     {:op :record-observations :effect :propose
                      :observations (poll 200 210 "Emacs" "activity.cljc")
                      :confidence 0.9}
                     (fresh-store))))))

;; ---------------------------------------------------------------------------
;; Self-capture
;; ---------------------------------------------------------------------------

(deftest hard-when-submitting-someone-elses-samples
  (let [st (fresh-store)]
    (store/grant-consent! st (store/consent "w-2" (at -1440) (at 1440) #{:app}))
    (let [v (check {:worker-id "w-1"}
                   {:op :record-observations :effect :propose :subject-worker "w-2"
                    :observations (poll 200 210 "Emacs" "activity.cljc") :confidence 0.9}
                   st)]
      (is (:hard? v))
      (is (some #(= :not-self-capture (:rule %)) (:violations v))))))

(deftest hard-when-labelling-someone-elses-session
  (let [st (fresh-store)]
    (store/grant-consent! st (store/consent "w-2" (at -1440) (at 1440) #{:app}))
    (let [v (check {:worker-id "w-1"} (assoc (clean-proposal) :subject-worker "w-2") st)]
      (is (:hard? v))
      (is (some #(= :not-self-capture (:rule %)) (:violations v))))))

;; ---------------------------------------------------------------------------
;; Observation basis — the advisor's arithmetic is never trusted
;; ---------------------------------------------------------------------------

(deftest hard-on-a-span-that-was-never-observed
  (let [v (check {:worker-id "w-1"}
                 (assoc (clean-proposal) :session/start (at 600) :session/end (at 900)
                        :duration-ms (* 300 min-ms))
                 (fresh-store))]
    (is (:hard? v))
    (is (some #(= :no-session (:rule %)) (:violations v)))))

(deftest hard-when-the-proposal-lengthens-observed-time
  (testing "an advisor claiming eight hours over a 100-minute session is held"
    (let [v (check {:worker-id "w-1"}
                   (assoc (clean-proposal) :duration-ms (* 8 3600000))
                   (fresh-store))]
      (is (:hard? v))
      (is (some #(= :duration-mismatch (:rule %)) (:violations v))))))

(deftest hard-on-an-undeclared-project
  (let [v (check {:worker-id "w-1"}
                 (assoc (clean-proposal) :project "megacorp-we-never-signed")
                 (fresh-store))]
    (is (:hard? v))
    (is (some #(= :unknown-project (:rule %)) (:violations v)))))

(deftest idle-time-cannot-be-attributed
  (testing "samples marked idle never segment, so citing that span is :no-session"
    (let [st (fresh-store)
          idle (mapv #(activity/observation (at %) :app "Emacs" :detail "activity.cljc" :idle? true)
                     (range 200 260))
          _ (store/record-observations! st "w-1" idle)
          v (check {:worker-id "w-1"}
                   (assoc (clean-proposal) :session/start (at 200) :session/end (at 260)
                          :duration-ms (* 60 min-ms))
                   st)]
      (is (:hard? v))
      (is (some #(= :no-session (:rule %)) (:violations v))))))

;; ---------------------------------------------------------------------------
;; Third-party disclosure
;; ---------------------------------------------------------------------------

(deftest hard-on-disclosing-another-workers-window-titles
  (let [st (fresh-store)]
    (store/grant-consent! st (store/consent "w-2" (at -1440) (at 1440) #{:app}))
    (let [v (check {:worker-id "w-1"}
                   {:op :disclose-report :effect :propose :subject-worker "w-2"
                    :include-detail? true :period-from (at 0) :period-to (at 100)
                    :confidence 0.9}
                   st)]
      (is (:hard? v))
      (is (some #(= :third-party-detail (:rule %)) (:violations v))))
    (testing "the same report without :obs/detail is allowed"
      (let [v (check {:worker-id "w-1"}
                     {:op :disclose-report :effect :propose :subject-worker "w-2"
                      :include-detail? false :period-from (at 0) :period-to (at 100)
                      :confidence 0.9}
                     st)]
        (is (:ok? v))))
    (testing "your own report may carry your own detail"
      (let [v (check {:worker-id "w-1"}
                     {:op :disclose-report :effect :propose :subject-worker "w-1"
                      :include-detail? true :period-from (at 0) :period-to (at 100)
                      :confidence 0.9}
                     st)]
        (is (:ok? v))))))

(deftest hard-on-a-report-about-a-worker-who-never-consented
  (let [v (check {:worker-id "w-1"}
                 {:op :disclose-report :effect :propose :subject-worker "w-2"
                  :include-detail? false :period-from (at 0) :period-to (at 100)
                  :confidence 0.9}
                 (fresh-store))]
    (testing "consent is the subject's to give, not the requester's"
      (is (:hard? v))
      (is (some #(= :no-consent (:rule %)) (:violations v))))))

;; ---------------------------------------------------------------------------
;; Escalation
;; ---------------------------------------------------------------------------

(deftest escalate-on-timesheet-submission
  (let [v (check {:worker-id "w-1"}
                 {:op :submit-timesheet :effect :propose
                  :period-from (at 0) :period-to (at 100)
                  :entries [] :confidence 0.95}
                 (fresh-store))]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (not (:ok? v)))))

(deftest escalate-on-low-confidence
  (let [v (check {:worker-id "w-1"} (assoc (clean-proposal) :confidence 0.3) (fresh-store))]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest low-confidence-escalates-but-a-hard-violation-still-wins
  (let [v (check {:worker-id "w-1"}
                 (assoc (clean-proposal) :confidence 0.3 :project "nope")
                 (fresh-store))]
    (is (:hard? v))
    (is (not (:escalate? v)))))
