#!/usr/bin/env nbb
;; capture.cljs — macOS capture agent for the kadou (稼働) actor.
;;
;; Polls what is in front of the worker and emits `kotoba.activity/observation`
;; maps, one EDN map per line, for `:op :record-observations`. It writes
;; nothing to the actor itself: the samples still have to pass the
;; KadouGovernor's consent check, and an agent that could commit its own
;; output would be the whole point defeated.
;;
;;   nbb --classpath ../../kotoba-lang/activity/src tools/capture.cljs \
;;       --worker w-1 --interval 30 --seconds 600 --out capture.edn
;;
;; Scopes mirror the consent scopes the governor enforces, and the default
;; is the narrow one:
;;
;;   --scopes app           frontmost application name only. Reads
;;                          `lsappinfo`, which needs NO accessibility
;;                          permission — nothing can see what you are
;;                          reading, only which app you are in.
;;   --scopes app,window    also the front window's title, via System
;;                          Events. This needs Accessibility permission
;;                          and it is the surveillance-shaped half of
;;                          capture: window titles carry document names,
;;                          ticket numbers, correspondents. Turn it on
;;                          for yourself, knowing that; `kotoba.activity/redact`
;;                          exists to take it back off before anything
;;                          is shared.
;;
;; A sample taken while the keyboard and mouse have been quiet longer than
;; --idle-after is marked :idle? true. Segmentation drops those, so an idle
;; stretch becomes a gap rather than billable time.

(require '["node:child_process" :as cp]
         '["node:fs" :as fs]
         '[clojure.string :as str]
         '[kotoba.activity :as activity])

;; ---------------------------------------------------------------------------
;; args
;; ---------------------------------------------------------------------------

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn- opt [flag default]
  (or (second (drop-while #(not= flag %) argv)) default))

(def worker-id (opt "--worker" nil))
(def scopes (set (map keyword (str/split (opt "--scopes" "app") #","))))
(def interval-ms (* 1000 (js/parseInt (opt "--interval" "30") 10)))
(def total-ms (* 1000 (js/parseInt (opt "--seconds" "0") 10)))
(def idle-after-ms (* 1000 (js/parseInt (opt "--idle-after" "180") 10)))
(def out-file (opt "--out" nil))

(when-not worker-id
  (println "usage: capture.cljs --worker <id> [--scopes app|app,window]"
           "[--interval sec] [--seconds sec] [--idle-after sec] [--out file]")
  (js/process.exit 2))

;; ---------------------------------------------------------------------------
;; probes
;; ---------------------------------------------------------------------------

(defn- run
  "Run a command, returning trimmed stdout or nil. Never throws: a probe
  that fails produces no sample, which segmentation reads as a gap. That
  is the honest failure — the alternative is inventing presence."
  [cmd args]
  (try
    (str/trim (.toString (cp/execFileSync cmd (clj->js args)
                                          #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "ignore"]})))
    (catch :default _ nil)))

(defn- frontmost-app
  "Frontmost application name via lsappinfo — no accessibility permission
  required."
  []
  (when-let [asn (run "lsappinfo" ["front"])]
    (when-let [line (run "lsappinfo" ["info" "-only" "name" asn])]
      ;; "LSDisplayName"="Ghostty"
      (second (re-find #"=\"(.*)\"\s*$" line)))))

(defn- front-window-title
  "Front window title via System Events. Requires Accessibility
  permission; returns nil when denied or when the app has no window."
  []
  (run "osascript"
       ["-e" (str "tell application \"System Events\" to tell "
                  "(first application process whose frontmost is true) "
                  "to get name of front window")]))

(defn- idle-ms
  "Milliseconds since the last HID event. IOKit reports nanoseconds."
  []
  (when-let [out (run "ioreg" ["-c" "IOHIDSystem"])]
    (when-let [ns-str (second (re-find #"\"HIDIdleTime\"\s*=\s*(\d+)" out))]
      (/ (js/parseFloat ns-str) 1e6))))

;; ---------------------------------------------------------------------------
;; sampling
;; ---------------------------------------------------------------------------

(def samples (atom []))

(defn- sample! []
  (when-let [app (frontmost-app)]
    (let [idle (idle-ms)
          detail (when (:window scopes) (front-window-title))
          obs (activity/observation (js/Date.now)
                                    (if (:window scopes) :window :app)
                                    app
                                    :detail detail
                                    :idle? (and idle (> idle idle-after-ms)))]
      (when obs
        (swap! samples conj obs)
        (let [line (pr-str obs)]
          (if out-file
            (fs/appendFileSync out-file (str line "\n"))
            (println line)))))))

(defn- summarise! []
  (let [obs @samples
        sessions (activity/segment obs)]
    (binding [*print-fn* *print-err-fn*]
      (println (str "\n" (count obs) " samples, " (count sessions) " sessions, "
                    (.toFixed (/ (activity/observed-ms sessions) 60000) 1)
                    " observed minutes"))
      (doseq [s sessions]
        (println (str "  " (:session/subject s)
                      (when (:session/detail s) (str " — " (:session/detail s)))
                      "  " (.toFixed (/ (:session/duration-ms s) 60000) 1) "m")))
      (when (and (seq obs) (empty? sessions))
        (println (str "  (nothing reached the "
                      (/ (:min-ms activity/default-segmentation) 60000)
                      "-minute floor — a short run samples but does not segment)")))
      (println (str "\nsubmit with :op :record-observations for worker " worker-id
                    " — the governor still has to find a consent covering "
                    (pr-str (vec scopes)) " over this span.")))))

(defn- stop! []
  (summarise!)
  (js/process.exit 0))

(.on js/process "SIGINT" stop!)

(sample!)
(let [timer (js/setInterval sample! interval-ms)]
  (when (pos? total-ms)
    (js/setTimeout (fn [] (js/clearInterval timer) (stop!)) total-ms)))
