# Near-Term Tasks

This file tracks only high-level current and upcoming tasks. The current section
can contain more than one active task. Keep detailed
designs and implementation steps in `docs/plans/`.

## Current

- [ ] Implement `docs/plans/2026-06-08-github-commit-replication.md`.
- [ ] Implement `docs/plans/2026-05-14-native-git-wire-protocol-core.md`:
      first replace the current Git transport parser path with a JGit-free
      streaming pkt-line reader that does not buffer whole packets or raw pack
      tails in additional memory.

## Next

- [ ] Native Git remote fetch and push support.
- [ ] GitHub mirror administration and manual sync.
- [ ] GitHub webhook-driven inbound synchronization.
