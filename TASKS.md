# Near-Term Tasks

This file tracks only high-level current and upcoming tasks. The current section
can contain more than one active task. Keep detailed
designs and implementation steps in `docs/plans/`.

## Current

- [ ] Implement `docs/plans/2026-06-08-github-commit-replication.md`.
- [ ] Implement `docs/plans/2026-07-26-native-git-end-to-end-backend.md`.
- [ ] Implement `docs/plans/2026-05-14-native-git-wire-protocol-core.md`:
      `f21b309` models `GitMinimalWireMachine` durable parser state as phase
      objects and documents its prototype role. Current work adds a
      `ByteBuf`-only `GitInitialServiceRequestParser` in `git-parser` that
      parses the initial data pkt-line by byte ranges without materializing a
      raw command string. The old `GitInternalService.parse(InputStream)` path
      temporarily keeps JGit `PacketLineIn` until a native transport/session can
      feed `ByteBuf` chunks into the wire core. Next, extend the native
      streaming path beyond the initial request without buffering whole packets
      or raw pack tails in additional memory.
  - [x] Extend the existing `GitMinimalWireMachine` with observable pkt-line
        control events and close-time validation instead of adding a parallel
        session API.
  - [x] Separate structured pkt-line payload handling from raw pack payload
        forwarding in the existing native wire machine.
  - [ ] Add typed Git wire errors with phase, offset or packet index, and
        malformed pkt-line error kinds.

## Next

- [ ] Native Git remote fetch and push support.
- [ ] GitHub mirror administration and manual sync.
- [ ] GitHub webhook-driven inbound synchronization.
