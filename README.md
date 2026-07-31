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

## Capture agents

Three collectors, all nbb, all emitting `kotoba.activity/observation` maps one
EDN map per line. None of them writes to the actor: the samples still have to
pass the governor's consent check.

### `tools/capture.cljs` — desktop

Polls what is in front of the worker.

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

### `tools/collect-vcs.cljs` — git

```bash
nbb --classpath ../../kotoba-lang/activity/src tools/collect-vcs.cljs \
    --worker w-1 --repo /path/to/repo --since 2026-07-01 --redact
```

**A commit becomes one instant, never a span.** A commit records when work was
*finished*; the interval between two commits is not time spent — 09:00 and 17:00
may be eight hours of work or two, with lunch and a meeting in between. So each
commit emits a single observation and `segment` gathers runs of them with the
same idle-gap rule it applies to app samples. An isolated commit produces a
session below the floor and is dropped. That undercounts, for the same reason
the desktop collector does: the alternative is inventing the interval.

Author date, not committer date — a rebase rewrites the committer date, and
rewriting history should not move when someone worked. `--redact` drops the
commit subject, which is the part that names the feature, the client, the bug.

### `tools/collect-calendar.cljs` — calendar

```bash
nbb --classpath ../../kotoba-lang/activity/src tools/collect-calendar.cljs \
    --worker w-1 --ics ~/calendar.ics --interval 5
```

**A file, not an API.** A calendar API would need an OAuth token with standing
read access to everything on someone's calendar, held by a capture agent and
refreshed forever — a much larger grant than "these events, this month", and one
the worker cannot easily take back. An export is a decision made once, for a
bounded range, with a file they can inspect first.

This is the one collector whose source knows both ends, so it dense-samples
*across* each event and segmentation reconstructs the span exactly. What it does
**not** assume is that the meeting happened, ran to time, or was attended;
declined events are excluded by default and the output says so in as many words.
Events with a floating or zoned `DTSTART` are read as UTC and counted in a
warning rather than silently shifted. Recurrence is not expanded — that needs a
timezone database and the exception rules, and a half-correct expansion would
invent occurrences that never happened.

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
| Tests | 53 tests, 171 assertions, all green |
| Capture agents | desktop (macOS), git, calendar — all exercised on real input |
| Store | `MemStore` + `DatomicStore` (langchain.db), proved interchangeable by a contract test |
| Deployment | Cloudflare Pages Functions — `POST /api/observations`, CACAO + allow-list gated |

## Store backends

`MemStore` (atom, zero deps) and `DatomicStore` (`langchain.db`) implement the
same protocol and pass the same contract test, so the actor, the governor and
the ledger never know which they run on. `DatomicStore` is pure `.cljc`: it runs
offline against an in-process DataScript, and the same record points at a real
Datomic or a kotoba-server pod by swapping `langchain.db`'s `:db-api`.

That contract test earned its keep immediately — it caught `MemStore`'s
`revoke-consent!` fabricating a consent record for a worker who never had one,
which the Datomic-backed store correctly declined to do. A phantom grant is the
worst possible thing to have near a hold that has no escalation path.

## HTTP surface

One route, permanently: `POST /api/observations`. A capture agent on a laptop
genuinely needs a network path; the other three ops end in a human's judgement,
so `:attribute-session`, `:submit-timesheet` and `:disclose-report` have no HTTP
representation at all — a test asserts their core fns do not exist.

Two gates, neither optional: a CACAO signature and temporal window
(`cacao.edge.verify`, the shared library, not reimplemented here), then an
allow-list that maps **DID → worker id**. The map rather than a set is what
carries the self-capture rule to the edge: the worker comes from the verified
DID and never from the request body, so a signed caller cannot submit someone
else's samples even before the governor sees the request.

**An absent allow-list serves 503, never an open endpoint.**

A second refusal sits in front of it: with `KADOU_STORE` unset the endpoint serves
**503 "no store configured"** without verifying anything. That is not caution for
its own sake — an empty in-process store fails the governor's registration check,
so the caller would get `409 :no-worker` and go looking at their own registration
while the actual fault is a deployment with no store. `KADOU_STORE=ephemeral`
enables a non-persisting smoke test, and every success response then carries
`"ephemeral": true`. A durable backend is not wired yet.

The deploy artifact is **built and exercised**, not merely configured:

```bash
npm install && npx shadow-cljs release edge-api   # -> functions/edge/
node -e "import('./functions/edge/...').then(m => m.observationsOnRequestPost(ctx))"
```

The `:esm` release runs `:advanced` optimization, which `cljs.main -c` does not,
so it is the first thing that could rename `KADOU_STORE`, `KADOU_CALLER_ALLOWLIST`
or `authorization` out from under the handler. They survive (`:infer-externs
:auto`), the module loads under Node, and invoking the export against a mock
Cloudflare context returns 503 unset, 503 on a typo'd store mode, and 401 for a
bad CACAO. Running it for real is also what surfaced a multi-line string literal
leaking source indentation into the JSON `hint`. A capture ingest
that defaults to open is a public write path into a store of personal data about
workers.

Build the edge bundle with `npx shadow-cljs release edge-api` (never `compile` —
its dev artifact imports a machine-local path and cannot load on a Pages Function
runtime), then ship `public/` with wrangler.

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later.
