# Near-Term Tasks

This file tracks only high-level current and upcoming tasks. The current section
can contain more than one active task. Keep detailed
designs and implementation steps in `docs/plans/`.

## Current

- [ ] Complete
      `docs/plans/2026-07-28-native-git-in-memory-server.md`: make one server
      WireMachine own the complete protocol conversation, back it with
      automatically created in-memory native repositories, add lifecycle/DI
      wiring, and cover native `git://` clone and push.
  - Owner: codex, started 2026-07-28 13:12 Europe/Amsterdam; implementing the
    WireMachine-centric in-memory server design from
    `docs/plans/2026-07-28-native-git-in-memory-server-design.md`.
## Next

- [ ] Add authenticated native repository resolution around the server
      WireMachine, including READ, WRITE, and CREATE checks before automatic
      repository creation.
- [ ] Connect receive-pack wire handling directly to the continuation-based
      `PackIngestor`, streaming `ByteBuf` fragments into the in-memory
      quarantine store and handing off the quarantine at the pack checksum
      checkpoint.
- [ ] Move native SSH and HTTP Git server paths onto `ByteBuf` transport
      adapters backed by the Continuation-based wire core, after the Netty
      `git://` path is complete.
- [ ] Implement production repository backends for native Git repository ports,
      including refs, objects, pack indexes, delta reconstruction, pack building,
      and projection parity.
- [ ] Before remote replication work, add native upload-pack and receive-pack
      client state machines on top of the Continuation-based `git-parser` wire
      machine. This outbound client path is not required by the native Git
      server clone/push work.
- [ ] Add the first real native Git client transport and end-to-end remote
      fetch/push compatibility tests after upload-pack and receive-pack client
      machines land.
- [ ] Implement `docs/plans/2026-06-08-github-commit-replication.md`, then add
      GitHub mirror administration, manual sync, and webhook-driven inbound
      synchronization.
