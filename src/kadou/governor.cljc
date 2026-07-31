(ns kadou.governor
  "KadouGovernor — the independent safety/traceability layer for the kadou
  (稼働) automatic work-time capture actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4313's payroll.governor, with the capture-specific
  twist that the governor RE-SEGMENTS the stored observations via
  `kotoba.activity` — the advisor's arithmetic about how long something
  took is never trusted.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. worker provenance  — the request's worker must be registered.
    2. no-actuation       — proposal :effect must be :propose.
    3. consent            — every op touching a worker's activity needs
                            that worker's active, unrevoked, in-scope
                            consent covering the span it touches — the
                            SUBJECT's grant, not the requester's. Absent
                            consent is what separates time capture from
                            surveillance, so it is deliberately NOT
                            escalatable: there is no human whose approval
                            substitutes for the worker's.
    4. self-capture       — a worker may submit and classify only their
                            own activity. Someone else labelling your day
                            is the surveillance move, not the timesheet.
    5. observation basis  — an :attribute-session proposal must cite a
                            span that re-segmenting the stored
                            observations actually produces, name a
                            declared project, and restate the observed
                            duration. Enforced by `activity/admit`, whose
                            :low-confidence verdict is downgraded to an
                            escalation here.
    6. third-party detail — a report about someone else may never carry
                            :obs/detail. Window titles and document names
                            are the surveillance payload; aggregate hours
                            about a colleague are a management question,
                            their reading list is not.
  ESCALATION invariants (:escalate? true, human sign-off):
    7. :op :submit-timesheet — the numbers leave the system and become a
                               billing basis.
    8. low confidence (< `confidence-floor`)."
  (:require [kotoba.activity :as activity]
            [kadou.store :as store]))

(def confidence-floor activity/default-confidence-floor)
(def ^:private escalating-ops #{:submit-timesheet})

;; Errors activity/admit can raise, split by how this actor treats them.
(def ^:private admit-escalations #{:low-confidence})

(defn- proposal-span
  "The [from to] window of activity a proposal touches, or nil when it
  touches none. nil spans fail the consent check rather than skipping it."
  [{:keys [:session/start :session/end observations period-from period-to]}]
  (cond
    (and start end)             [start end]
    (seq observations)          [(apply min (map :obs/at observations))
                                 (apply max (map :obs/at observations))]
    (and period-from period-to) [period-from period-to]
    :else                       nil))

(defn- proposal-scopes
  "Which capture sources a proposal needs consent for. Only ingestion
  names new sources; downstream ops inherit whatever was already
  admitted."
  [{:keys [op observations]}]
  (if (= :record-observations op)
    (into #{} (map :obs/source) observations)
    #{}))

(def ^:private self-only-ops #{:record-observations :attribute-session})

(defn- hard-violations [request proposal store]
  (let [{:keys [op effect subject-worker include-detail?]} proposal
        worker-id      (:worker-id request)
        worker-record  (store/worker store worker-id)
        span           (proposal-span proposal)
        subject        (or subject-worker worker-id)
        ;; Consent belongs to the person being observed, not the person
        ;; asking. A manager requesting a report about a colleague needs
        ;; that colleague's grant, not their own.
        c              (store/consent-of store subject)
        sessions       (activity/segment (store/observations-of store worker-id))
        project-ids    (into #{} (map :project/id) (store/projects store))
        admit          (when (= :attribute-session op)
                         (activity/admit proposal sessions project-ids
                                         {:confidence-floor confidence-floor}))
        admit-hard     (remove #(admit-escalations (:rule %)) (:admit/errors admit))]
    (cond-> []
      (nil? worker-record)
      (conj {:rule :no-worker :detail "未登録 worker"})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (not (store/consent-covers? c span (proposal-scopes proposal)))
      (conj {:rule :no-consent
             :detail (str "capture consent が span " (pr-str span)
                          " / scopes " (pr-str (proposal-scopes proposal))
                          " を覆っていない（同意なき記録は勤怠計測ではなく監視）")})

      (and (self-only-ops op) (not= subject worker-id))
      (conj {:rule :not-self-capture
             :detail (str "worker " worker-id " が " subject
                          " の稼働を記録／分類しようとした（" (name op) " は自己観測のみ）")})

      (seq admit-hard)
      (into (map (fn [e] {:rule (:rule e) :detail (:detail e)}) admit-hard))

      (and (= :disclose-report op) (not= subject worker-id) include-detail?)
      (conj {:rule :third-party-detail
             :detail (str "他人（" subject "）の報告に :obs/detail は含められない"
                          "（`kotoba.activity/redact` を通すこと）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `kadou.store/Store`. Pure — never mutates the store.
  Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request _context proposal store]
  (let [hard      (hard-violations request proposal store)
        hard?     (boolean (seq hard))
        conf      (or (:confidence proposal) 0.0)
        low?      (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok?        (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard?      hard?
     :escalate?  (and (not hard?) (or low? risky-op?))}))
