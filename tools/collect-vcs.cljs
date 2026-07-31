#!/usr/bin/env nbb
;; collect-vcs.cljs — version-control collector for the kadou (稼働) actor.
;;
;; Emits `kotoba.activity/observation` maps from a git repository's own
;; history, one EDN map per line, for `:op :record-observations`. Like
;; tools/capture.cljs it writes nothing to the actor: the samples still
;; have to pass the KadouGovernor's consent check.
;;
;;   nbb --classpath ../../kotoba-lang/activity/src tools/collect-vcs.cljs \
;;       --worker w-1 --repo /path/to/repo --since 2026-07-01 --out vcs.edn
;;
;; ## Why a commit becomes ONE observation and not a span
;;
;; A commit records when work was FINISHED, never when it started. The
;; interval between two commits is not time spent — a commit at 09:00 and
;; one at 17:00 may be eight hours of work or two, with lunch and a
;; meeting in between. So each commit emits a single instant, and
;; `kotoba.activity/segment` gathers runs of them into sessions using the
;; same idle-gap rule it applies to app samples. Two commits an hour apart
;; produce a one-hour session; a commit on its own produces a session
;; shorter than the floor and is dropped.
;;
;; That undercounts, exactly as the app collector does, and for the same
;; reason: the alternative is inventing the interval.
;;
;; Author date is used rather than committer date — a rebase rewrites the
;; committer date, and rewriting history should not move when someone
;; worked.

(require '["node:child_process" :as cp]
         '["node:fs" :as fs]
         '[clojure.string :as str]
         '[kotoba.activity :as activity])

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn- opt [flag default]
  (or (second (drop-while #(not= flag %) argv)) default))

(def worker-id (opt "--worker" nil))
(def repo (opt "--repo" "."))
(def since (opt "--since" "30 days ago"))
(def author (opt "--author" nil))
(def out-file (opt "--out" nil))
(def redact? (boolean (some #{"--redact"} argv)))

(when-not worker-id
  (println "usage: collect-vcs.cljs --worker <id> [--repo dir] [--since date]"
           "[--author pattern] [--redact] [--out file]")
  (js/process.exit 2))

(defn- run [cmd args]
  (try
    (str/trim (.toString (cp/execFileSync cmd (clj->js args)
                                          #js {:encoding "utf8"
                                               :stdio #js ["ignore" "pipe" "ignore"]})))
    (catch :default _ nil)))

(defn- repo-name []
  (or (some-> (run "git" ["-C" repo "rev-parse" "--show-toplevel"])
              (str/split #"/") last)
      "repo"))

(defn- log-lines []
  (let [args (cond-> ["-C" repo "log" "--no-merges"
                      ;; %at = author date, unix seconds; %s = subject
                      "--pretty=format:%at\t%s" (str "--since=" since)]
               author (conj (str "--author=" author)))]
    (some-> (run "git" args) (str/split-lines))))

(defn- ->observation [line]
  (let [[at subject] (str/split line #"\t" 2)
        ms (* 1000 (js/parseInt at 10))]
    (when (and (js/isFinite ms) (pos? ms))
      (activity/observation ms :vcs (repo-name)
                            ;; The commit subject is the sensitive part —
                            ;; it names the feature, the client, the bug.
                            ;; --redact drops it exactly as
                            ;; kotoba.activity/redact would.
                            :detail (when-not redact? subject)))))

(defn- summarise! [obs sessions]
  (binding [*print-fn* *print-err-fn*]
    (println (str "\n" (count obs) " commits, " (count sessions) " sessions, "
                  (.toFixed (/ (activity/observed-ms sessions) 60000) 1)
                  " observed minutes"))
    (doseq [s sessions]
      (println (str "  " (:session/subject s)
                    (when (:session/detail s) (str " — " (:session/detail s)))
                    "  " (.toFixed (/ (:session/duration-ms s) 60000) 1) "m"
                    " (" (:session/samples s) " commits)")))
    (when (and (seq obs) (empty? sessions))
      (println (str "  (no run of commits reached the "
                    (/ (:min-ms activity/default-segmentation) 60000)
                    "-minute floor — isolated commits carry no measurable span)")))
    (println (str "\nsubmit with :op :record-observations for worker " worker-id
                  " — the governor still has to find a consent covering [:vcs]"
                  " over this span."))))

(let [lines (or (log-lines) [])
      obs (vec (keep ->observation lines))]
  (when (empty? lines)
    (binding [*print-fn* *print-err-fn*]
      (println (str "no commits in " repo " since " since
                    " (or not a git repository)"))))
  (doseq [o obs]
    (let [line (pr-str o)]
      (if out-file
        (fs/appendFileSync out-file (str line "\n"))
        (println line))))
  (summarise! obs (activity/segment obs)))
