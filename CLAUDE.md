# CLAUDE.md — cloud-itonami/kadou 稼働

Automatic work-time capture actor. itonami pattern: advisor ⊣ independent
governor ⊣ append-only ledger. Domain arithmetic is `kotoba-lang/activity`; this
repo is the governed shell plus three capture agents.

## The rule that is not negotiable

**The consent hold has no escalation path.** Everywhere else in this fleet a
hard hold means "a human has to look at this". Here it means there is no human
whose approval substitutes for the worker's own. Do not add an approval route
around `:no-consent`, and do not let `:record-observations` or
`:attribute-session` name a `:subject-worker` other than the requester.

`:third-party-detail` is the same shape: aggregate hours about a colleague are a
management question, their window titles are not.

Consent belongs to the **subject**, not the requester. `:app` is not `:window`.

## What the model may do

Label a span. Nothing else. The governor re-segments the stored observations
through `kotoba.activity/segment`; the advisor's duration is never trusted.
`llm-advisor` redacts `:session/detail` unless the caller opts in.

## Collectors write nothing

`tools/*.cljs` emit EDN and have no path to the store — samples still pass the
consent check. Two modelling rules to keep:

- **A commit is an instant, not a span.** It records when work *finished*; the
  gap between two commits is not time spent.
- **The calendar collector reads a FILE, not an API.** An API needs a standing
  token over everything on someone's calendar. Do not "improve" this to OAuth.

## Store and edge

`MemStore` ≡ `DatomicStore` — same protocol, same contract test. Write both
sides of any store change; that test has already caught a `MemStore` bug the
Datomic side did not have.

The HTTP surface is **one route**: `POST /api/observations`. The escalating ops
have no HTTP representation and a test asserts their core fns do not exist. An
absent allow-list serves **503**, never an open endpoint, and so does an unset
`KADOU_STORE` — refusing beats returning `:no-worker` and blaming the caller
for a deployment that has no store.

## Test

    clojure -M:test && clojure -M:lint
