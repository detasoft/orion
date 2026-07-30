# Near-Term Tasks

This file tracks only high-level current and upcoming tasks. The current section
can contain more than one active task. Keep detailed
designs and implementation steps in `docs/plans/`.

## Current

- [x] Complete Task 0 from
      `docs/plans/2026-07-28-native-git-in-memory-server.md`: replace the
      internals of `GitMinimalWireMachine` with composed Continuations and add
      the minimal Netty Yield handler.
- [x] Flatten the Task 0 Git wire continuation graph: remove nested child
      runners, start directly from the header continuation, and move each wire
      continuation into its own class.
- [x] Add the process-local native Git repository provider with isolated
      in-memory refs and objects, without machine/continuation integration.
- [x] Implement legacy receive-pack advertisement and fragment-safe command
      parsing with an explicit raw pack handoff boundary.

## Next

- [ ] Complete Tasks 1–7 from
      `docs/plans/2026-07-28-native-git-in-memory-server.md`: make one server
      WireMachine own the complete protocol conversation, back it with
      automatically created in-memory native repositories, add lifecycle/DI
      wiring, and cover native `git://` clone and push.
  - Owner: codex, started 2026-07-30 02:30 Europe/Amsterdam; current:
    wire lifecycle/DI and cover native `git://` clone now that legacy
    upload-pack streams repository packs through side-band-64k.
- [ ] Add authenticated native repository resolution around the server
      WireMachine, including READ, WRITE, and CREATE checks before automatic
      repository creation.
- [x] Connect receive-pack wire handling directly to the continuation-based
      `PackIngestor`, streaming `ByteBuf` fragments into the in-memory
      quarantine store and handing off the quarantine at the pack checksum
      checkpoint.
- [ ] Move native SSH and HTTP Git server paths onto `ByteBuf` transport
      adapters backed by the Continuation-based wire core, after the Netty
      `git://` path is complete.
- [x] Multiplex native Git side-band channels in `GitNativeClientOutput`:
      interleave DATA pack production with ordered PROGRESS and ERROR messages
      through one backpressured response and one outbound transport.
- [ ] Replace copied native client output chunks with the completion-aware
      buffering contract from
      `docs/plans/2026-07-30-completion-aware-native-client-output.md`: land
      double buffering first, then add the ring-buffer coordinator as a
      separate slice.
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
- [ ] Extend protocol v2 server fetch beyond the base request: add shallow
      history, object filters, ref-in-want, sideband-all, wait-for-done
      negotiation, and packfile URI responses.
  - Owner: codex, started 2026-07-30 14:13 Europe/Amsterdam; current:
    implement wait-for-done negotiation with repository-backed ACK/NAK responses.
- [ ] Implement `docs/plans/2026-06-08-github-commit-replication.md`, then add
      GitHub mirror administration, manual sync, and webhook-driven inbound
      synchronization.
