# kadou operator guide

A worker's day, end to end, with the checks each step has to pass.

## 0. Register

```clojure
(require '[kadou.store :as store] '[kadou.actor :as actor] '[kotoba.activity :as activity])

(def st (store/mem-store))
(store/register-worker! st {:worker/id "w-1" :worker/name "Rin"})
(store/register-project! st {:project/id "kotoba-activity" :project/name "activity lib"})
```

Projects are the closed set attribution may name. An advisor proposing anything
else is held with `:unknown-project`, which is what keeps a hallucinated client
out of an invoice.

## 1. Consent

Nothing works before this, by design.

```clojure
(store/grant-consent! st (store/consent "w-1" from until #{:app}))
```

`from`/`until` are epoch milliseconds, and `scopes` are `kotoba.activity/sources`
the worker agreed to have sampled. Consenting to `:app` is **not** consenting to
`:window` — the window title is the part that says what you were reading, so it
is a separate grant.

```clojure
(store/revoke-consent! st "w-1" (System/currentTimeMillis))
```

Revocation at time R invalidates everything at or after R. Time already observed
and already attributed stays in the ledger rather than being silently rewritten:
withdrawing consent stops future capture, it does not retroactively unbill last
Tuesday.

## 2. Capture

```bash
nbb --classpath ../../kotoba-lang/activity/src tools/capture.cljs \
    --worker w-1 --interval 30 --seconds 28800 --out capture.edn
```

The agent writes EDN lines and nothing else. It has no path to the store; the
samples are ingested as an operation like any other, so an agent left running
past a consent window produces samples that are simply held.

```clojure
(actor/run-request! g {:worker-id "w-1" :op :record-observations
                       :observations samples} {} "thread-1")
```

Held here means **not written**. A `:no-consent` hold leaves
`(store/observations-of st "w-1")` empty and one `:hold` entry in the ledger.

## 3. Attribution rules

Registered per worker, because they are that worker's own declaration of what
their apps mean:

```clojure
(store/register-rule! st "w-1"
  (activity/rule :emacs-activity "kotoba-activity"
                 :source :app :subject-is "Emacs"
                 :detail-contains "activity" :weight 0.9))
```

Every clause given must match. A rule with no clauses matches nothing, so an
accidental catch-all cannot be written by omission. When two rules of equal
weight name different projects the session is left unattributed and flagged
`:attribution/ambiguous?` rather than resolved by list order.

## 4. Attribute a session

```clojure
(actor/run-request! g {:worker-id "w-1" :op :attribute-session
                       :session/start start} {} "thread-2")
```

The governor re-segments the stored observations and admits the proposal against
what that produces. Three ways this is held:

| | |
|---|---|
| `:no-session` | the cited span is not one segmentation produces — including a span made only of idle samples |
| `:duration-mismatch` | the proposal restates a different duration |
| `:unknown-project` | the project is not registered |

A session no rule matches is proposed with `:project nil` and confidence `0.0`.
That escalates rather than being held: an unattributed session is a real
question for the worker, not a violation. Resume with `actor/approve!` once they
have said what it was.

## 5. Submit

```clojure
(actor/run-request! g {:worker-id "w-1" :op :submit-timesheet
                       :period-from from :period-to to
                       :date-of (fn [ms] "2026-01-01")}  ;; caller owns the timezone
                    {} "thread-3")
;; => {:status :interrupted ...}
(actor/approve! g "thread-3")
```

`:submit-timesheet` always interrupts. The numbers are about to become a billing
basis, and that is a human's signature, not a model's.

Read `[:proposal :coverage]` before approving:

```clojure
{:coverage/observed-ms 7200000 :coverage/attributed-ms 3600000
 :coverage/unattributed-ms 3600000 :coverage/ratio 0.5}
```

A half-attributed day reads as 0.5. The missing hour is absent from the entries
rather than folded into the largest project — if it should be billed, add a rule
or attribute the session, do not round it in.

Entries carry `:ts/worker`, `:ts/date` and `:ts/hours`, which is exactly what
`kotoba.labor/wages-for` reads:

```clojure
(require '[kotoba.labor :as labor])
(labor/wages-for (labor/contract "c-1" "w-1" "emp-1" "engineer" :hourly 12000)
                 (get-in result [:state :proposal :entries]))
```

## 6. Reports about someone else

```clojure
(actor/run-request! g {:worker-id "manager-1" :op :disclose-report
                       :subject-worker "w-1" :include-detail? false
                       :period-from from :period-to to} {} "thread-4")
```

Two things have to hold, and neither is escalatable:

- **the subject's** consent covers the period — not the requester's;
- `:include-detail?` is false. Aggregate hours about a colleague are a
  management question; their window titles are not.

## Reading the ledger

```clojure
(store/ledger st)
;; [{:disposition :hold :verdict {:violations [{:rule :no-consent :detail "..."}]}}
;;  {:disposition :commit :record {...}}]
```

Every proposal that was refused is in there with the rule that refused it. A
capture system whose holds are invisible is a capture system nobody can audit.
