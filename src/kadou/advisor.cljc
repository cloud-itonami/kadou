(ns kadou.advisor
  "KadouAdvisor — proposes what a captured stretch of activity was FOR.
  Swappable: `mock-advisor` (deterministic, rule-driven, default) or
  `llm-advisor`. Either way the advisor ONLY produces a PROPOSAL;
  `kadou.governor` independently re-segments the stored observations via
  `kotoba.activity` and holds anything citing time that was not observed.
  Modeled on cloud-itonami-isco-4313's payroll.advisor.

  The division of labour with the model is the whole design: the model
  may LABEL a span, and nothing else. It never states how long the span
  was — that comes from re-segmentation — and it cannot name a project
  outside the declared set. An LLM that hallucinates a nine-hour session
  on a client that does not exist produces a proposal that fails
  admission on both counts, which is a held ledger entry rather than an
  invoice.

  A proposal is a map:
    {:op :record-observations|:attribute-session|:submit-timesheet|:disclose-report
     :effect :propose
     :session/start n :session/end n :duration-ms n   ; :attribute-session
     :project str                                     ; :attribute-session
     :observations [obs]                              ; :record-observations
     :entries [ts]                                    ; :submit-timesheet
     :subject-worker str :include-detail? bool        ; :disclose-report
     :confidence 0.0-1.0
     :rationale str}"
  (:require [kotoba.activity :as activity]
            [kadou.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- session-at
  "The observed session starting at `start`, re-segmented from what the
  store actually holds."
  [store worker-id start]
  (first (filter #(= start (:session/start %))
                 (activity/segment (store/observations-of store worker-id)))))

(defn- infer
  "Deterministic mock inference. For :attribute-session the honest advisor
  attributes via the worker's own `kotoba.activity` rules (an LLM advisor
  labels the same span in prose); the governor re-segments independently
  either way."
  [store {:keys [op worker-id subject-worker] :as request}]
  (let [base (cond-> {:op op
                      :effect :propose
                      :confidence 0.95
                      :rationale (str "proposed " (name op) " for worker " worker-id)}
               ;; Carried through verbatim so the governor sees who the
               ;; request was really about — an advisor cannot launder a
               ;; request for someone else's activity by dropping the key.
               subject-worker (assoc :subject-worker subject-worker))]
    (case op
      :attribute-session
      (let [s   (session-at store worker-id (:session/start request))
            att (when s (activity/attribute s (store/rules-of store worker-id)))]
        (assoc base
               :session/start (:session/start s)
               :session/end   (:session/end s)
               :duration-ms   (:session/duration-ms s)
               :project       (:attribution/project att)
               :confidence    (or (:attribution/confidence att) 0.0)
               :rationale     (str "rules " (pr-str (:attribution/evidence att))
                                   " matched " (pr-str (:session/subject s)))))

      :record-observations
      (assoc base :observations (:observations request))

      :submit-timesheet
      (let [sessions (activity/segment (store/observations-of store worker-id))
            att      (activity/attribute-all sessions (store/rules-of store worker-id))]
        (assoc base
               :period-from (:period-from request)
               :period-to   (:period-to request)
               :entries     (activity/->timesheet-entries
                             worker-id (:date-of request) att)
               :coverage    (activity/coverage att)))

      :disclose-report
      (assoc base
             :subject-worker  (:subject-worker request)
             :include-detail? (boolean (:include-detail? request))
             :period-from     (:period-from request)
             :period-to       (:period-to request))

      base)))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a work-time attribution advisor. Given an observed session —
   its app, window title and span — propose which of the DECLARED
   projects it belongs to, an honest :confidence, and a short :rationale.
   Restate :session/start, :session/end and :duration-ms exactly as
   given. Never invent a project, never change a duration, never fill a
   gap. If nothing fits, propose :project nil with a low confidence.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`; decoupled from any concrete model
  beyond the protocol. The session handed to the model is REDACTED unless
  the caller opts in — `:obs/detail` is what makes attribution accurate
  and also what makes it surveillance, so sending it off-device is a
  decision, not a default."
  ([chat-model model-generate-fn gen-opts]
   (llm-advisor chat-model model-generate-fn gen-opts {}))
  ([chat-model model-generate-fn gen-opts {:keys [send-detail?]}]
   (reify Advisor
     (-advise [_ store request]
       (let [worker-id (:worker-id request)
             s (session-at store worker-id (:session/start request))
             visible (cond-> s (not send-detail?) (assoc :session/detail nil))
             msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "declared projects: "
                                              (pr-str (mapv :project/id (store/projects store)))
                                              "\nobserved session: " (pr-str visible))}]
             resp (model-generate-fn chat-model msgs gen-opts)]
         ;; The model's own restatement of the span is discarded in favour
         ;; of the observed one. It could not lengthen time even if it
         ;; tried, and the governor re-checks this independently.
         (merge (parse-proposal (:content resp))
                {:op            :attribute-session
                 :session/start (:session/start s)
                 :session/end   (:session/end s)
                 :duration-ms   (:session/duration-ms s)}))))))
