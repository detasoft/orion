# Separate Git Parser from Native Storage

Status: todo
Plan: [Git wire architecture simplification](../../../2026-09-03-git-wire-architecture-simplification.md)

Restore `git-parser` as a storage-neutral Git wire module and keep
`git-native-storage` as a distinct repository implementation module.

## Scope

- Remove the `git-parser` dependency on `git-native-storage` and every native
  storage type from the parser's public and internal production surfaces.
- Keep pkt-line framing, wire bootstrap parsing, protocol serialization, and
  storage-neutral wire values in `git-parser`.
- Move Orion server session, repository, authorization, pack production, pack
  ingestion, and packfile-URI coordination to `net/git-transport`.
- Make native TCP, SSH, and Smart HTTP bootstrap initiators explicitly compose
  the wire objects with native storage-backed server objects.
- Keep `git-client` able to consume `git-parser` without receiving native
  storage transitively.
- Preserve protocol bytes, failure behavior, streaming, and resource ownership
  while changing module ownership.

## Completion Criteria

- `git-parser` compiles and tests without `git-native-storage` on its Maven
  dependency graph.
- `net/git-transport` declares every storage dependency it uses directly.
- Native TCP, SSH, and Smart HTTP tests cover the explicit bootstrap
  composition path.
