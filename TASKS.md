# Near-Term Tasks

This file tracks only high-level current and upcoming tasks. The current section
can contain more than one active task. Keep detailed
designs and implementation steps in `docs/plans/`.

## Current

- [ ] Complete
      `docs/plans/2026-07-28-netty-transport-wire-machine-phase2.md`: connect
      `GitNativeProtocolAdapter` to native repository services, stream
      structured sections and pack data through the Continuation-based wire
      machine, add lifecycle/DI wiring, and cover native `git://` clone and push.
## Next

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
