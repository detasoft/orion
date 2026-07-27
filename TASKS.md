# Near-Term Tasks

This file tracks only high-level current and upcoming tasks. The current section
can contain more than one active task. Keep detailed
designs and implementation steps in `docs/plans/`.

## Current

- [ ] Native receive-pack/push vertical slice: ref atomic updates, quarantine,
      pack ingestion, and Git CLI push compatibility tests. Active next task.
- [ ] Implement the first native Git clone vertical slice from
      `docs/plans/2026-07-26-native-git-end-to-end-backend.md`: native
      upload-pack, read-only ref/object access, response pack building, one
      native transport path, and a Git CLI `clone` compatibility test.
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
  - [x] Add typed Git wire errors with phase, offset or packet index, and
        malformed pkt-line error kinds.
  - [x] Add a native pkt-line writer for data, flush, delimiter, and
        response-end packets, with binary payload and explicit text helpers.
  - [x] Add capability parsing and writing for v0/v1 advertisement lines and
        protocol v2 capability lines, preserving unknown capabilities and
        values.
  - [x] Add a protocol v2 section parser for command, argument, delimiter,
        flush, response-end, and protocol error packets.
  - [x] Add side-band and side-band-64k helpers that demultiplex pack data,
        progress, and fatal error bands without buffering pack payloads as
        structured text.
  - [x] Add report-status parsing and writing for unpack status plus ok/ng
        per-ref command results.
  - [ ] Add a reference advertisement parser for the v1 wire protocol:
        first-line `<sha1> <ref>\0<caps>`, subsequent ref lines, peeled tags,
        and empty-repository sentinel, backed by `GitCapabilityParser`.
  - [ ] Add protocol v2 response parsers: `ls-refs` response (ref list with
        optional symref and peeled fields) and `fetch` response (sectioned
        acknowledgments, shallow-info, wanted-refs, and packfile side-band
        entry point), terminated by response-end packet.

## Next

- [ ] Native Git remote fetch and push support.
- [ ] Implement `docs/plans/2026-06-08-github-commit-replication.md`.
- [ ] GitHub mirror administration and manual sync.
- [ ] GitHub webhook-driven inbound synchronization.
