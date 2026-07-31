# Governance

Maintained by the cloud-itonami org. The actor pattern (advisor-LLM ⊣
independent governor, append-only audit ledger, ADR-2607011000) is
non-negotiable; external-send actions require human approval.

One rule here is stronger than the fleet default and is not a matter of
maintainer discretion: **the consent check is a hard hold with no escalation
path.** Everywhere else in this fleet a hard hold means a human has to look at
it. Here it means there is no human whose approval substitutes for the worker's
own. A pull request that adds an approval route around `:no-consent`, or that
lets `:record-observations` / `:attribute-session` name a `:subject-worker`
other than the requester, will be closed regardless of the use case it cites.

`:third-party-detail` is the same shape: aggregate hours about a colleague are a
management question, their window titles are not. Widening a report to carry
`:obs/detail` about someone else needs a new named op, a new consent scope the
subject grants explicitly, and an ADR — not a flag.
