(ns kadou.edge.endpoints-test
  "The portable half of the edge layer, tested on the JVM with no
  platform involved. The `:cljs` `on-request-*` entry point contains no
  policy — it parses a Cloudflare `context` and calls the core fn below —
  so everything worth asserting is here."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.activity :as activity]
            [kadou.store :as store]
            [kadou.edge.endpoints :as edge]))

(def ^:private min-ms 60000)
(def ^:private t0 1767225600000)
(defn- at [m] (+ t0 (* m min-ms)))

(def ^:private morning
  (mapv #(activity/observation (at %) :app "Emacs" :detail "activity.cljc")
        (range 0 101)))

(def ^:private allowlist {"did:key:zAlice" "w-1" "did:key:zBob" "w-2"})

(defn- seeded []
  (let [st (store/mem-store)]
    (store/register-worker! st {:worker/id "w-1" :worker/name "Rin"})
    (store/register-worker! st {:worker/id "w-2" :worker/name "Kei"})
    (store/grant-consent! st (store/consent "w-1" (at -1440) (at 1440) #{:app}))
    st))

(defn- body [obs] (pr-str {:observations (vec obs)}))

;; ---------------------------------------------------------------------------
;; The allow-list is not optional
;; ---------------------------------------------------------------------------

(deftest an-absent-allowlist-serves-503-not-an-open-endpoint
  (testing "a capture ingest that defaults to open is a public write path
            into a store of personal data about workers"
    (let [r (edge/record-observations-core! (seeded) nil "did:key:zAlice" (body morning))]
      (is (= 503 (:status r)))
      (is (= "no allow-list configured" (get-in r [:body :error]))))))

(deftest parse-allowlist-distinguishes-empty-from-absent
  (is (nil? (edge/parse-allowlist nil)))
  (is (nil? (edge/parse-allowlist "")))
  (is (nil? (edge/parse-allowlist "   ")))
  (testing "a malformed entry does not silently become an empty allow-list"
    (is (nil? (edge/parse-allowlist "garbage-without-an-equals"))))
  (is (= {"did:key:zAlice" "w-1"} (edge/parse-allowlist "did:key:zAlice=w-1")))
  (is (= {"did:key:zAlice" "w-1" "did:key:zBob" "w-2"}
         (edge/parse-allowlist " did:key:zAlice = w-1 , did:key:zBob = w-2 "))))

(deftest an-unlisted-caller-is-refused
  (let [r (edge/record-observations-core! (seeded) allowlist "did:key:zMallory" (body morning))]
    (is (= 403 (:status r)))))

;; ---------------------------------------------------------------------------
;; The self-capture rule reaches the edge
;; ---------------------------------------------------------------------------

(deftest a-caller-can-only-ever-write-as-their-own-worker
  (testing "the allow-list is a map, not a set, so the worker id comes from
            the verified DID and never from the request body"
    (let [st (seeded)
          ;; Bob signs, and tries to smuggle w-1 into the body.
          r (edge/record-observations-core!
             st allowlist "did:key:zBob"
             (pr-str {:worker "w-1" :observations (vec morning)}))]
      (testing "the body's :worker is ignored entirely — Bob acts as w-2"
        (is (= 409 (:status r)))
        (is (some #(= :no-consent (:rule %)) (get-in r [:body :violations]))))
      (testing "and nothing landed under w-1"
        (is (empty? (store/observations-of st "w-1")))))))

;; ---------------------------------------------------------------------------
;; Body
;; ---------------------------------------------------------------------------

(deftest an-unparseable-or-empty-body-is-400
  (doseq [bad ["" "not edn (((" "{:observations []}" "{:observations nil}" "[1 2 3]"]]
    (is (= 400 (:status (edge/record-observations-core! (seeded) allowlist "did:key:zAlice" bad)))
        (str "should reject " (pr-str bad)))))

(deftest edn-is-read-not-evaluated
  (testing "clojure.edn/read-string evaluates nothing, so a reader-macro
            payload is a parse failure and not an execution"
    (is (= 400 (:status (edge/record-observations-core!
                         (seeded) allowlist "did:key:zAlice"
                         "#=(java.lang.Runtime/getRuntime)"))))))

;; ---------------------------------------------------------------------------
;; The governor still decides
;; ---------------------------------------------------------------------------

(deftest a-consented-ingest-commits-and-reports-the-running-total
  (let [st (seeded)
        r (edge/record-observations-core! st allowlist "did:key:zAlice" (body morning))]
    (is (= 200 (:status r)))
    (is (= 101 (get-in r [:body :recorded])))
    (is (= 101 (get-in r [:body :total])))
    (is (= 101 (count (store/observations-of st "w-1"))))))

(deftest a-second-ingest-accumulates
  (let [st (seeded)]
    (edge/record-observations-core! st allowlist "did:key:zAlice" (body (subvec morning 0 10)))
    (let [r (edge/record-observations-core! st allowlist "did:key:zAlice" (body (subvec morning 10)))]
      (is (= 200 (:status r)))
      (is (= 101 (get-in r [:body :total]))))))

(deftest an-unconsented-ingest-is-409-with-the-reason
  (testing "a capture agent whose samples are refused needs to know why"
    (let [st (seeded)
          _ (store/revoke-consent! st "w-1" (at -1))
          r (edge/record-observations-core! st allowlist "did:key:zAlice" (body morning))]
      (is (= 409 (:status r)))
      (is (some #(= :no-consent (:rule %)) (get-in r [:body :violations])))
      (testing "and nothing was written"
        (is (empty? (store/observations-of st "w-1")))))))

(deftest a-scope-the-consent-does-not-cover-is-refused-at-the-governor
  (let [st (seeded)
        windows (mapv #(activity/observation (at %) :window "Emacs" :detail "salary.md")
                      (range 0 10))
        r (edge/record-observations-core! st allowlist "did:key:zAlice" (body windows))]
    (testing "consent to :app is not consent to :window, edge or not"
      (is (= 409 (:status r)))
      (is (some #(= :no-consent (:rule %)) (get-in r [:body :violations]))))))

;; ---------------------------------------------------------------------------
;; What is NOT on the surface
;; ---------------------------------------------------------------------------

(deftest the-escalating-ops-have-no-http-representation
  (testing "there is no core fn for them, which is the point — a caller
            cannot drive the actor to the edge of a human's judgement"
    (let [publics (set (keys (ns-publics 'kadou.edge.endpoints)))]
      (is (contains? publics 'record-observations-core!))
      (doseq [absent '[submit-timesheet-core! attribute-session-core!
                       disclose-report-core!]]
        (is (not (contains? publics absent)) (str absent " must not exist"))))))
