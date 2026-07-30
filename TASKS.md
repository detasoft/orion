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

- [ ] Finish the native `git://` server path around `GitMinimalWireMachine` and
      the Netty transport: back it with automatically created in-memory native
      repositories, add lifecycle/DI wiring, and cover native clone and push.
  - Owner: codex, started 2026-07-30 02:30 Europe/Amsterdam; current:
    wire lifecycle/DI and cover native `git://` clone now that legacy
    upload-pack streams repository packs through side-band-64k.
- [ ] Delete obsolete native Git plan/design references, including
      `docs/plans/2026-07-28-native-git-in-memory-server.md`,
      `docs/plans/2026-07-28-native-git-in-memory-server-design.md`, and
      `docs/plans/2026-07-28-netty-transport-wire-machine-phase2.md`.
- [ ] Add authenticated native repository resolution around the
      `GitMinimalWireMachine`/Netty server path, including READ, WRITE, and
      CREATE checks before automatic repository creation.
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
- [ ] Extend protocol v2 server fetch with shallow history support beyond the
      base request.
  - Owner: codex, paused 2026-07-30 14:21 Europe/Amsterdam; wait-for-done
    negotiation is implemented; next: add shallow history support.
- [ ] Extend protocol v2 server fetch with object filter support.
- [ ] Extend protocol v2 server fetch with ref-in-want support.
- [ ] Extend protocol v2 server fetch with sideband-all support.
- [ ] Extend protocol v2 server fetch with packfile URI responses.
- [ ] Implement `docs/plans/2026-06-08-github-commit-replication.md`, then add
      GitHub mirror administration, manual sync, and webhook-driven inbound
      synchronization.
