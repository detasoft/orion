# Implement Production Native Repository Backends

Status: todo
Source: converted from former root task list Current section.
Owner: codex, session legacy-unknown, paused 2026-08-04 19:45 Europe/Amsterdam.
Next: add pack index publication and pack-backed object reads.

Implement production repository backends for native Git repository ports,
including refs, objects, pack indexes, delta reconstruction, pack building, and
projection parity.

## Child Tasks

- [x] [Add file-backed native repository provider](file-backed-native-repository-provider/TASK.md)
- [ ] [Publish received packs with durable pack indexes](published-pack-indexes/TASK.md)
- [ ] [Read objects from published pack files through pack indexes](pack-index-object-reads/TASK.md)
- [ ] [Reconstruct packed delta objects on demand](packed-delta-reconstruction/TASK.md)
- [x] [Build production pack files](production-pack-building/TASK.md)
- [ ] [Add delta pack optimization](delta-pack-optimization/TASK.md)
- [ ] [Add thin-pack optimization](thin-pack-optimization/TASK.md)
- [ ] [Match native repository projections with JGit behavior](projection-parity/TASK.md)
- [ ] [Define atomic publication boundaries](atomic-publication-boundaries/TASK.md)
