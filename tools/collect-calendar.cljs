#!/usr/bin/env nbb
;; collect-calendar.cljs — calendar collector for the kadou (稼働) actor.
;;
;; Emits `kotoba.activity/observation` maps from an iCalendar (.ics) file,
;; one EDN map per line, for `:op :record-observations`. Writes nothing to
;; the actor: the samples still have to pass the KadouGovernor's consent
;; check.
;;
;;   nbb --classpath ../../kotoba-lang/activity/src tools/collect-calendar.cljs \
;;       --worker w-1 --ics ~/calendar.ics --from 2026-07-01 --out cal.edn
;;
;; ## A FILE, not an API
;;
;; This reads an .ics export deliberately. Connecting to a calendar API
;; would need an OAuth token with standing read access to everything on
;; someone's calendar, held by a capture agent, refreshed forever — which
;; is a much larger grant than "these events, this month" and one the
;; worker cannot easily take back. An export is a decision the worker
;; makes once, for a bounded range, with a file they can inspect first.
;;
;; ## A meeting IS a span, unlike a commit
;;
;; This is the one collector whose source knows both ends. A calendar
;; event has a start and an end that someone agreed to, so the observation
;; stream is dense-sampled ACROSS the event at `--interval` rather than
;; marking a single instant. Segmentation then reconstructs the span
;; exactly, and no interval is invented.
;;
;; What is NOT assumed: that the meeting happened, that it ran to time, or
;; that the invitee attended. `--accepted-only` (the default) at least
;; restricts to events this worker did not decline. A meeting on a
;; calendar is evidence of an intention; treating it as evidence of work
;; is how calendar-derived timesheets inflate.

(require '["node:fs" :as fs]
         '[clojure.string :as str]
         '[kotoba.activity :as activity])

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn- opt [flag default]
  (or (second (drop-while #(not= flag %) argv)) default))

(def worker-id (opt "--worker" nil))
(def ics-path (opt "--ics" nil))
(def interval-ms (* 60000 (js/parseInt (opt "--interval" "5") 10)))
(def out-file (opt "--out" nil))
(def redact? (boolean (some #{"--redact"} argv)))
(def all-events? (boolean (some #{"--include-declined"} argv)))

(when-not (and worker-id ics-path)
  (println "usage: collect-calendar.cljs --worker <id> --ics <file>"
           "[--interval min] [--include-declined] [--redact] [--out file]")
  (js/process.exit 2))

;; ---------------------------------------------------------------------------
;; iCalendar parsing
;;
;; A deliberately small subset: VEVENT blocks, DTSTART/DTEND/SUMMARY, and
;; the PARTSTAT on this worker's ATTENDEE line. Recurrence (RRULE) is NOT
;; expanded — an unexpanded recurring event yields only its first
;; instance, which undercounts. Expanding it correctly needs a timezone
;; database and the exception rules, and a half-correct expansion would
;; invent occurrences that never happened.
;; ---------------------------------------------------------------------------

(defn- unfold
  "RFC 5545 line folding: a leading space continues the previous line."
  [text]
  (-> text (str/replace #"\r\n[ \t]" "") (str/replace #"\n[ \t]" "")))

(defn- parse-dt
  "DTSTART/DTEND value -> epoch ms. Handles the UTC form (…Z) and the
  floating/local form, which is read as UTC and flagged."
  [v]
  (when-let [m (re-find #"(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z?)" v)]
    (let [[_ y mo d h mi s z] m
          ms (.UTC js/Date (js/parseInt y 10) (dec (js/parseInt mo 10))
                   (js/parseInt d 10) (js/parseInt h 10)
                   (js/parseInt mi 10) (js/parseInt s 10))]
      {:ms ms :floating? (not= "Z" z)})))

(defn- prop [block name]
  (some->> (str/split-lines block)
           (filter #(str/starts-with? % (str name ":")))
           first
           (#(subs % (inc (count name))))))

(defn- prop-with-params [block name]
  (some->> (str/split-lines block)
           (filter #(or (str/starts-with? % (str name ":"))
                        (str/starts-with? % (str name ";"))))
           first))

(defn- declined? [block]
  (when-let [line (prop-with-params block "ATTENDEE")]
    (boolean (re-find #"PARTSTAT=DECLINED" line))))

(defn- events [text]
  (for [block (rest (str/split (unfold text) #"BEGIN:VEVENT"))
        :let [start (some-> (or (prop block "DTSTART")
                                (some-> (prop-with-params block "DTSTART")
                                        (str/split #":") last))
                            parse-dt)
              end   (some-> (or (prop block "DTEND")
                                (some-> (prop-with-params block "DTEND")
                                        (str/split #":") last))
                            parse-dt)
              summary (or (prop block "SUMMARY") "(no summary)")]
        :when (and start end (< (:ms start) (:ms end)))
        :when (or all-events? (not (declined? block)))]
    {:start (:ms start) :end (:ms end) :summary summary
     :floating? (or (:floating? start) (:floating? end))}))

;; ---------------------------------------------------------------------------
;; Sampling across an event
;; ---------------------------------------------------------------------------

(defn- event->observations [{:keys [start end summary]}]
  (keep (fn [t]
          (activity/observation t :calendar summary
                                :detail (when-not redact? summary)))
        (concat (range start end interval-ms) [end])))

(defn- summarise! [evs obs sessions floating]
  (binding [*print-fn* *print-err-fn*]
    (println (str "\n" (count evs) " events, " (count obs) " samples, "
                  (count sessions) " sessions, "
                  (.toFixed (/ (activity/observed-ms sessions) 60000) 1)
                  " observed minutes"))
    (doseq [s sessions]
      (println (str "  " (:session/subject s) "  "
                    (.toFixed (/ (:session/duration-ms s) 60000) 1) "m")))
    (when (pos? floating)
      (println (str "\nWARNING: " floating " event(s) carried a floating or"
                    " zoned DTSTART and were read as UTC. If your calendar is"
                    " not UTC those spans are shifted — export with UTC times"
                    " or correct them before submitting.")))
    (println (str "\nNOTE: a calendar event is evidence that a meeting was"
                  " SCHEDULED, not that it happened, ran to time, or that you"
                  " attended. Review before submitting."))
    (println (str "submit with :op :record-observations for worker " worker-id
                  " — the governor still has to find a consent covering"
                  " [:calendar] over this span."))))

(let [text (try (.toString (fs/readFileSync ics-path "utf8"))
                (catch :default e
                  (binding [*print-fn* *print-err-fn*]
                    (println (str "cannot read " ics-path ": " (.-message e))))
                  (js/process.exit 2)))
      evs (vec (events text))
      obs (vec (mapcat event->observations evs))
      floating (count (filter :floating? evs))]
  (doseq [o obs]
    (let [line (pr-str o)]
      (if out-file
        (fs/appendFileSync out-file (str line "\n"))
        (println line))))
  (summarise! evs obs (activity/segment obs) floating))
