# Implement Production Native Repository Backends

Status: todo
Source: converted from former root task list Current section.
Next: add thin-pack optimization.

Implement production repository backends for native Git repository ports,
including refs, objects, pack indexes, delta reconstruction, pack building, and
projection parity.

## Child Tasks

- [x] [Add file-backed native repository provider](file-backed-native-repository-provider/TASK.md)
- [x] [Publish received packs with durable pack indexes](published-pack-indexes/TASK.md)
- [x] [Read objects from published pack files through pack indexes](pack-index-object-reads/TASK.md)
- [x] [Reconstruct packed delta objects on demand](packed-delta-reconstruction/TASK.md)
- [x] [Build production pack files](production-pack-building/TASK.md)
- [x] [Add delta pack optimization](delta-pack-optimization/TASK.md)
- [ ] [Add thin-pack optimization](thin-pack-optimization/TASK.md)
- [ ] [Match native repository projections with JGit behavior](projection-parity/TASK.md)
- [ ] [Define atomic publication boundaries](atomic-publication-boundaries/TASK.md)
