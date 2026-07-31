# Contributing

The domain arithmetic — observations, segmentation, attribution, admission,
rounding — lives in the capability library `kotoba-lang/activity`. This repo
holds the governed actor and the capture agent. Fixes to how time is *counted*
belong upstream; fixes to what may be *committed* belong here.

Keep production capture, attribution and timesheet submission behind the
governor. Nothing may write to the store outside the `:commit` node.

Three invariants come from `kotoba-lang/activity` and are load-bearing here.
A change that breaks one is a change to what this actor claims about the world:

1. Time is never extrapolated past the last observation.
2. Unattributed time stays unattributed — nothing is spread to make a day add up.
3. Rounding goes down or to nearest, never up.

Before opening a PR:

```bash
clojure -M:lint
clojure -M:test
```

`GOVERNANCE.md` lists the rules that are not up for discussion, chiefly that the
consent hold has no escalation path.
