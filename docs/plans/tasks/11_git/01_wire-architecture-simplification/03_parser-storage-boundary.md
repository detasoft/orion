# Separate Git Parser from Native Storage

Status: todo

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

## Detailed implementation plan

### Task 4: Establish the parser/storage module boundary

**Files:**

- Modify: `git/git-parser/pom.xml`
- Modify: `net/git-transport/pom.xml`
- Create: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitServerWireOutput.java`
- Move from `git-parser` to `net/git-transport`: `GitBlockingWireSession.java`,
  `GitNativeRepositoryService.java`, `GitNativeRepositoryAccessHook.java`,
  `NativePackfileUriSourceFactory.java`, and storage-coupled exchange types
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java`
- Modify: affected tests under `git/git-parser`, `net/git-transport`,
  `net/http-core`, and `tests/git-engine-orion-adapters`

1. Add or move characterization tests so server session behavior is owned by
   `net/git-transport`, while pkt-line and storage-neutral serialization tests
   remain in `git-parser`.
2. Move the Orion server session, repository port, access hook, packfile-URI
   factory, and native-storage exchange values to the transport module without
   changing behavior.
3. Extract `GitServerWireOutput` around the storage-dependent response methods;
   keep only raw pkt-line read/write and storage-neutral values in
   `GitBlockingWireTransport`.
4. Add an explicit `git-native-storage` dependency to `net/git-transport` and
   remove it from `git-parser`.
5. Update native TCP, SSH, Smart HTTP, Dagger, and workflow-test imports so the
   class that starts bootstrap also assembles the server session with its
   storage-backed collaborators.
6. Run focused parser, client, transport, HTTP, and Orion adapter tests.
7. Inspect the resolved client graph:

   ```bash
   mvn dependency:tree -Pdev -pl git/git-client \
     -Dincludes=pro.deta.orion.git:git-native-storage
   ```

   Expected: no `git-native-storage` dependency beneath `git-client`.
8. Inspect parser production imports:

   ```bash
   rg -n "git\.nativestorage" git/git-parser/src/main/java git/git-parser/pom.xml
   ```

   Expected: no matches.
9. Commit the module-boundary migration and tests.
