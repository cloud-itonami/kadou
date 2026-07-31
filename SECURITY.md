# Security Policy

Unlike the blueprint-only repos in this fleet, this one ships a capture agent
that runs on a real machine and reads what is in front of the person using it.

- `tools/capture.cljs` writes only to stdout or the `--out` file you name. It
  makes no network call and cannot reach the actor.
- `--scopes app` (the default) reads `lsappinfo` and `ioreg` and needs no
  special permission. `--scopes app,window` reads window titles via System
  Events and needs macOS Accessibility permission — that is the setting that
  turns application names into document names, ticket numbers and
  correspondents.
- Captured samples are personal data about a worker. `kotoba.activity/redact`
  drops `:obs/detail`; run anything leaving the worker's device through it.
- The `MemStore` default holds everything in process memory and persists
  nothing. Any backend added later inherits the governor's consent check —
  it is checked before the write, not after.

No credentials and no client data live in this repo. Report privately to
root@junkawasaki.com.
