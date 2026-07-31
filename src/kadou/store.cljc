(ns kadou.store
  "SSoT for the kadou (稼働) automatic work-time capture actor. Store is a
  protocol injected into the `kadou.actor` StateGraph — `MemStore` is the
  default, deterministic, zero-dep backend (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4313's payroll.store; the activity-domain records
  (observations, sessions, attributions, timesheet entries) use
  `kotoba.activity`'s record shapes verbatim — this actor CONSUMES
  kotoba-lang/activity, it does not reinvent segmentation or rounding.

  Domain:

    worker      — a registered worker (:worker/id, :worker/name). Capture
                  is self-observation: a worker's samples may only be
                  submitted by that worker.
    consent     — a time-bounded, scope-bounded, revocable grant to
                  capture. The ONLY thing that makes an observation
                  admissible. Without it this actor is surveillance, so
                  the governor treats its absence as a hard hold rather
                  than something a human approver can wave through.
    project     — a declared project. Attribution may only name one of
                  these; the advisor cannot invent a client.
    observation — a `kotoba.activity/observation`, per worker,
                  append-only. The ONLY admissible basis for any claim
                  about how long something took (no invented time).
    rule        — a `kotoba.activity/rule`, per worker. The worker's own
                  declaration of what their apps mean, not a policy
                  imposed on them.
    record      — a committed operating record — written ONLY via
                  commit-record!.
    ledger      — append-only audit trail of every proposal/verdict/
                  disposition, commit or hold.")

(defprotocol Store
  (worker [s worker-id])
  (consent-of [s worker-id])
  (projects [s])
  (rules-of [s worker-id])
  (observations-of [s worker-id])
  (records-of [s worker-id])
  (ledger [s])
  (register-worker! [s w])
  (grant-consent! [s consent])
  (revoke-consent! [s worker-id at])
  (register-project! [s p])
  (register-rule! [s worker-id rule])
  (record-observations! [s worker-id observations])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (worker [_ worker-id] (get-in @a [:workers worker-id]))
  (consent-of [_ worker-id] (get-in @a [:consents worker-id]))
  (projects [_] (vals (:projects @a)))
  (rules-of [_ worker-id] (get-in @a [:rules worker-id] []))
  (observations-of [_ worker-id] (get-in @a [:observations worker-id] []))
  (records-of [_ worker-id] (filter #(= worker-id (:worker-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-worker! [s w]
    (swap! a assoc-in [:workers (:worker/id w)] w) s)
  (grant-consent! [s consent]
    (swap! a assoc-in [:consents (:consent/worker consent)] consent) s)
  (revoke-consent! [s worker-id at]
    (swap! a update-in [:consents worker-id] assoc :consent/revoked-at at) s)
  (register-project! [s p]
    (swap! a assoc-in [:projects (:project/id p)] p) s)
  (register-rule! [s worker-id rule]
    (swap! a update-in [:rules worker-id] (fnil conj []) rule) s)
  (record-observations! [s worker-id observations]
    (swap! a update-in [:observations worker-id] (fnil into []) observations) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:workers {} :consents {} :projects {}
                                    :rules {} :observations {}
                                    :records [] :ledger []}
                                   seed)))))

;; ---------------------------------------------------------------------------
;; Consent
;; ---------------------------------------------------------------------------

(defn consent
  "Construct a capture consent. `from`/`until` are epoch milliseconds and
  `scopes` the `kotoba.activity/sources` the worker agreed to have
  sampled — consenting to :app is not consenting to :window, because the
  window title is the part that says what you were reading."
  [worker-id from until scopes]
  {:consent/worker     worker-id
   :consent/from       from
   :consent/until      until
   :consent/scopes     (set scopes)
   :consent/revoked-at nil})

(defn consent-covers?
  "Does `c` admit capture over `[from to]` for `scopes`? A revocation at
  time R invalidates everything at or after R — revoking consent stops
  future capture, and time already observed and already attributed stays
  in the ledger rather than being silently rewritten."
  [c [from to] scopes]
  (boolean
   (and c
        (number? from) (number? to)
        (>= from (:consent/from c))
        (<= to (:consent/until c))
        (or (nil? (:consent/revoked-at c)) (< to (:consent/revoked-at c)))
        (every? (:consent/scopes c) scopes))))
