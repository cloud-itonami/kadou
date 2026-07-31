(ns kadou.edge.endpoints
  "The HTTP surface kadou exposes — exactly one route:

      POST /api/observations   ingest a capture agent's samples

  and nothing else, permanently. Per `manifest/repository-rules.edn` an
  itonami actor is `:on-demand`: it answers a request and stops. It holds
  no loop and needs no clock, so Cloudflare Pages Functions is the shape,
  the same one `cloud-itonami-isic-6492` already proves in production.

  ## Why only this route

  The fleet invariant — no actor auto-drives another actor's own governed
  lifecycle — decides the surface. Of kadou's four ops:

    :record-observations  auto-commits when consent covers it   → exposed
    :attribute-session    may commit, may escalate              → not exposed
    :submit-timesheet     ALWAYS escalates (billing basis)      → not exposed
    :disclose-report      would let a caller ask over HTTP for
                          a report about someone else           → not exposed

  A capture agent running on a laptop genuinely needs a network path;
  the other three end in a human's judgement, and putting them behind an
  HTTP call would mean a caller could drive the actor to the edge of that
  judgement automatically. So the route that exists is the one that has
  to, and the escalating ops have no HTTP representation at all.

  ## Two gates, and neither is optional

  1. CACAO signature + temporal window (`cacao.edge.verify`, the shared
     library — this ns does NOT reimplement it; ADR-2607268000).
  2. The verified caller DID must be in the allow-list, which maps DID →
     worker id. It is a MAP and not a set on purpose: it is what carries
     the self-capture rule to the edge, so a signed caller cannot submit
     someone else's samples even before the governor sees the request.

  **An absent allow-list serves 503, never an open endpoint.** A capture
  ingest that defaults to open is a public write path into a store of
  personal data about workers.

  The portable `*-core!` fns take an already-verified caller and a store,
  so they are testable on the JVM with no platform involved; the
  `:cljs`-only `on-request-*` entry points do nothing but parse the
  Cloudflare `context` and produce a `js/Response`."
  (:require [kadou.actor :as actor]
            [kadou.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:cljs [cacao.edge.verify :as cacao])))

;; ---------------------------------------------------------------------------
;; Allow-list
;; ---------------------------------------------------------------------------

(defn parse-allowlist
  "`\"did:key:z6Mk…=w-1,did:key:z6Ml…=w-2\"` -> `{did worker-id}`.

  Returns nil for a blank or absent value rather than an empty map, so a
  caller cannot confuse 'nobody is allowed' with 'nothing was
  configured' — the first is a deployment decision and the second is a
  deployment mistake, and they get different status codes."
  [s]
  (when (and (string? s) (seq (.trim s)))
    (let [pairs (keep (fn [entry]
                        (let [[did worker] (map #(.trim %) (.split entry "="))]
                          (when (and did worker (seq did) (seq worker))
                            [did worker])))
                      (.split (.trim s) ","))]
      (when (seq pairs) (into {} pairs)))))

(defn worker-for
  "The worker id a verified caller may act as, or nil."
  [allowlist did]
  (get allowlist did))

;; ---------------------------------------------------------------------------
;; Body
;; ---------------------------------------------------------------------------

(defn parse-body
  "EDN request body -> `{:observations [...]}`, or nil when it is not a
  map with a non-empty observation vector.

  EDN rather than JSON because that is what the collectors emit
  (`tools/capture.cljs`, `tools/collect-vcs.cljs`,
  `tools/collect-calendar.cljs`) and because a JSON round-trip would have
  to guess how to turn `\"obs/at\"` back into a namespaced keyword. Read
  with `clojure.edn/read-string`, which evaluates nothing."
  [s]
  (try
    (let [m (edn/read-string s)]
      (when (and (map? m) (vector? (:observations m)) (seq (:observations m)))
        m))
    (catch #?(:clj Exception :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; Store selection
;; ---------------------------------------------------------------------------

(defn store-mode
  "How this deployment is configured to store what it accepts, from the
  `KADOU_STORE` env var.

    nil          nothing configured
    :ephemeral   an in-process store that does not survive the request

  Returns nil for anything else, including an unrecognised value — a typo
  in a deployment variable must not silently select a storage mode.

  Portable (takes a plain map) so the decision is testable without a
  platform; the `:cljs` entry point converts Cloudflare's `env` into one."
  [env]
  (case (some-> (get env "KADOU_STORE") .trim)
    "ephemeral" :ephemeral
    nil))

(defn store-unconfigured-response
  "What to serve when no store mode is configured.

  Deliberately 503 and NOT an empty in-process store. An empty store makes
  every request fail the governor's registration check, so the caller is
  told `:no-worker` — blamed for a deployment that has no store at all.
  Misattributed blame is worse than a refusal: the operator goes looking
  at their own registration while the actual fault is here."
  []
  {:status 503
    :body {:ok false :error "no store configured"
           ;; One line. A multi-line string literal here leaks the source
           ;; file's indentation into the JSON body, which the release
           ;; build made visible the first time this ran for real.
           :hint (str "bind a durable store, or set KADOU_STORE=ephemeral"
                      " for a non-persisting smoke test")}})

(defn record-observations-core!
  "`POST /api/observations`.

  `caller-did` is ALREADY verified by the time this runs. Returns
  `{:status n :body m}`.

    503  no allow-list configured — the endpoint refuses to serve rather
         than serve openly
    403  the caller is not on the allow-list
    400  unparseable or empty body
    409  the governor held it; the violations are in the body, because a
         capture agent whose samples are being refused needs to know why
    200  committed"
  [store mode allowlist caller-did raw-body]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (worker-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (if-let [body (parse-body raw-body)]
      (let [worker-id (worker-for allowlist caller-did)
            g (actor/build-graph {:store store})
            r (actor/run-request! g {:worker-id worker-id
                                     :op :record-observations
                                     :observations (:observations body)}
                                  {} (str "edge-" caller-did "-" (count (:observations body))))
            disposition (get-in r [:state :disposition])]
        (if (= :commit disposition)
          {:status 200
           :body {:ok true :ephemeral (= :ephemeral mode) :worker worker-id
                  :recorded (count (:observations body))
                  :total (count (store/observations-of store worker-id))}}
          {:status 409
           :body {:ok false :disposition disposition
                  :violations (mapv #(select-keys % [:rule :detail])
                                    (get-in r [:state :verdict :violations]))}}))
      {:status 400 :body {:ok false :error "invalid request body"}})))

;; ---------------------------------------------------------------------------
;; Cloudflare Pages Function entry point
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- json-response [{:keys [status body]}]
     (js/Response. (js/JSON.stringify (clj->js body))
                   #js {:status status
                        :headers #js {"content-type" "application/json"}})))

#?(:cljs
   (defn on-request-post-observations
     "Parses the Cloudflare `context`, verifies the CACAO in the
     `authorization` header, and hands an already-verified caller to
     `record-observations-core!`. Contains no policy of its own — every
     decision above is made by the core fn or by the governor beneath it.

     Refuses BEFORE verifying anything when no store mode is configured:
     a deployment with no store cannot honour a write, and an empty
     in-process store would fail the governor's registration check and
     blame the caller for it."
     [context]
     (let [env (aget context "env")
           mode (store-mode {"KADOU_STORE" (aget env "KADOU_STORE")})
           allowlist (parse-allowlist (aget env "KADOU_CALLER_ALLOWLIST"))
           header (or (.get (aget (aget context "request") "headers") "authorization") "")
           token (if (.startsWith header "Bearer ") (subs header 7) header)]
       (-> (js/Promise.all
            #js [(cacao/verify token) (.text (aget context "request"))])
           (.then (fn [results]
                    (let [v (aget results 0)
                          raw (aget results 1)]
                      (cond
                        (nil? mode)
                        (json-response (store-unconfigured-response))

                        (not (:valid v))
                        (json-response {:status 401
                                                        :body {:ok false :error "invalid or expired CACAO"}})

                        :else
                        (json-response (record-observations-core! (store/mem-store) mode
                                                      allowlist (:iss v) raw))))))
           (.catch (fn [e]
                     (json-response {:status 500
                                     :body {:ok false :error "request failed"
                                            :reason (ex-message e)}})))))))
