# kadou 稼働

**Automatic work-time capture, with a governor that will not let it become
surveillance.** The cloud-itonami fleet's answer to the Timely / Memtime / Rize /
Clockk / DeskTime / RescueTime category — automatic capture of what work
actually took — built on the itonami actor pattern (advisor-LLM ⊣ independent
governor, append-only audit ledger, ADR-2607011000).

稼働 = *time in operation*: the hours a thing was actually running, as opposed to
the hours someone said it was.

```text
capture agent ──▶ :record-observations ─┐
                                        ├─▶ KadouAdvisor ─▶ KadouGovernor ─▶ commit | approve | HOLD
worker's rules ──▶ :attribute-session ──┘                          │
                                                                   └─▶ append-only ledger
```

The domain arithmetic lives in
[`kotoba-lang/activity`](https://github.com/kotoba-lang/activity) — observations,
segmentation, attribution, admission, timesheet emission, all pure `.cljc`. This
repo is the governed shell around it, and the capture agent that feeds it.

## Why a governor at all

The products in this category are one design decision away from being employee
monitoring, and several of them took that step. Automatic capture needs to see
your screen; a timesheet needs to leave your machine; a manager wants a report.
Each of those is reasonable alone, and together they are a surveillance system.

kadou draws the line in the governor, where it is checkable, rather than in a
privacy policy:

| | HARD hold — never overridable |
|---|---|
| `:no-worker` | the requesting worker is not registered |
| `:no-actuation` | proposal `:effect` is not `:propose` |
| `:no-consent` | the **subject's** consent does not cover this span and these scopes |
| `:not-self-capture` | someone is submitting or labelling another worker's activity |
| `:no-session` | the proposal cites a span that re-segmenting the stored observations does not produce |
| `:duration-mismatch` | the proposal restates a duration other than the observed one |
| `:unknown-project` | the proposal names a project outside the declared set |
| `:third-party-detail` | a report about someone else carries `:obs/detail` |

Consent is deliberately **not escalatable**. Everywhere else in the fleet a hard
hold means "a human has to look at this"; here it means there is no human whose
approval substitutes for the worker's own. A manager cannot approve their way
into capturing someone who did not agree, and the escalation path does not exist
to be found.

| | escalate — human sign-off |
|---|---|
| `:submit-timesheet` | the numbers leave the system and become a billing basis |
| low confidence | below 0.6, including the 0.0 an unmatched session gets |

## What the model is allowed to do

Label a span. That is all.

The advisor never states how long something took — `kadou.governor` re-segments
the stored observations through `kotoba.activity/segment` and admits the proposal
against what that produces. An LLM that hallucinates a nine-hour session on a
client that does not exist fails admission on both the duration and the project,
and lands in the ledger as a hold rather than in an invoice. `llm-advisor` also
redacts `:session/detail` before the prompt unless the caller passes
`{:send-detail? true}`: window titles are what make attribution accurate and also
what make it surveillance, so sending them off-device is a decision, not a
default.

## Capture agent

`tools/capture.cljs` (nbb, macOS) polls what is in front of the worker and emits
`kotoba.activity/observation` maps, one EDN map per line. It writes nothing to
the actor — the samples still have to pass the governor's consent check.

```bash
nbb --classpath ../../kotoba-lang/activity/src tools/capture.cljs \
    --worker w-1 --interval 30 --seconds 600 --out capture.edn
```

Scopes mirror the consent scopes:

- `--scopes app` (default) — frontmost application name via `lsappinfo`. **No
  Accessibility permission required**; nothing can see what you are reading, only
  which app you are in.
- `--scopes app,window` — also the front window title, via System Events. Needs
  Accessibility permission, and it is the surveillance-shaped half.

A sample taken while keyboard and mouse have been quiet longer than
`--idle-after` (default 180s) is marked `:idle? true`. Segmentation drops those,
so an idle stretch becomes a gap rather than billable time.

## Operations

```clojure
(require '[kadou.store :as store] '[kadou.actor :as actor] '[kotoba.activity :as activity])

(def st (store/mem-store))
(store/register-worker! st {:worker/id "w-1" :worker/name "Rin"})
(store/grant-consent! st (store/consent "w-1" from until #{:app}))
(store/register-project! st {:project/id "kotoba-activity"})
(store/register-rule! st "w-1"
  (activity/rule :emacs "kotoba-activity"
                 :source :app :subject-is "Emacs" :detail-contains "activity" :weight 0.9))

(def g (actor/build-graph {:store st}))

(actor/run-request! g {:worker-id "w-1" :op :record-observations
                       :observations samples} {} "thread-1")
(actor/run-request! g {:worker-id "w-1" :op :attribute-session
                       :session/start start} {} "thread-2")
(actor/run-request! g {:worker-id "w-1" :op :submit-timesheet
                       :period-from from :period-to to
                       :date-of (fn [ms] ...)} {} "thread-3")   ;; interrupts
(actor/approve! g "thread-3")
```

The attribution rules are registered **per worker**: they are that worker's own
declaration of what their apps mean, not a policy applied to them.

## Maturity

| | |
|---|---|
| Role | actor (advisor ⊣ governor ⊣ ledger) |
| Capability library | `kotoba-lang/activity` (sibling path) |
| Tests | 26 tests, 79 assertions, all green |
| Capture agent | macOS, exercised on real `lsappinfo` / `ioreg` output |
| Store | `MemStore` only — no Datomic/kotoba-server backend yet |
| Deployment | none — no endpoint, no scheduled loop |

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later.
