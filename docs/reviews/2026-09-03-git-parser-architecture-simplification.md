# Git Parser Architecture Simplification Review

Date: 2026-09-03
Status: baseline saved; re-review pending
Follow-up task: [Re-audit the simplified Git wire architecture](../plans/upcoming-work/git-wire-architecture-simplification/post-simplification-review/TASK.md)

## Review Scope

Read-only `architecture-simplifier` review of `git/git-parser` and its direct
server, storage, client, SSH, native TCP, and Smart HTTP consumers. The review
looked for mixed ownership, multiple sources of truth, continuation-era state,
repeated coordination, overly weak value types, and concepts that can be
removed after the current blocking/virtual-thread migration.

This report is the baseline for a second review after the linked implementation
tasks finish. An accepted direction below is not marked resolved until that
review verifies the resulting code and dependency graph.

## Findings and Accepted Directions

### 1. `git-parser` mixes wire core with the Orion storage-backed server

Status: accepted; implementation pending
Task: [Separate `git-parser` from native storage](../plans/upcoming-work/git-wire-architecture-simplification/parser-storage-boundary/TASK.md)

Evidence:

- `git/git-parser/pom.xml` directly depends on `git-native-storage`.
- `GitBlockingWireSession`, `GitNativeRepositoryService`, repository access,
  pack ingestion/production, and packfile-URI decisions live in the parser.
- `git-client` depends on `git-parser`, so the server storage dependency is
  inherited by a wire client that does not need it.
- Native TCP, SSH, and Smart HTTP entrypoints already initiate wire bootstrap,
  making them the natural places to compose wire and storage-backed server
  behavior.

Accepted direction:

- Keep `git-parser` storage-neutral.
- Keep `git-native-storage` as its own module and declare it directly from the
  Orion server/transport owner.
- Move Orion-specific server session and repository coordination to
  `net/git-transport` rather than creating a new `git-server` module now.
- Require the entrypoint that starts wire bootstrap to assemble the parser and
  storage-backed command objects explicitly.

### 2. Capability advertisement has multiple sources of truth

Status: accepted; implementation pending
Task: [Add a global capability advertisement policy](../plans/upcoming-work/git-wire-architecture-simplification/global-capability-advertisement-policy/TASK.md)

Evidence:

- `GitCapability` defines canonical legacy names and valued forms.
- `GitWireConfiguration` duplicates availability as nested protocol booleans.
- `DefaultGitNativeRepositoryService` assembles legacy capability lists, while
  `GitBlockingWireTransport` independently assembles protocol v2 advertisement
  strings and subfeatures.
- Bootstrap entrypoints repeatedly choose `allSupported()`, so a future decision
  not to advertise one capability must be applied in several places.

Accepted direction:

- Add one immutable, server-wide advertisement policy that can globally veto a
  canonical capability name.
- The policy is not an operational runtime switch and cannot enable a feature;
  protocol code still determines what is implemented.
- Apply the same deny decision to every transport and protocol advertisement,
  and use the effective advertised set during negotiation validation.

### 3. Blocking output retains resumable serialization state

Status: accepted with deliberately narrow scope; implementation pending
Task: [Complete blocking output migration](../plans/upcoming-work/git-wire-architecture-simplification/blocking-output-migration/TASK.md)

Evidence:

- `OutputSerialization` remains as an operation object even though `writeTo`
  now performs a synchronous blocking write.
- `AsciiPacketSequenceSerialization`, `PacketListSerialization`, and
  `PktLineSerialization` retain intermediate payload/packet collections and
  cursor fields inherited from resumable delivery.
- `LegacySideBandResponse`, `LegacyPackResponse`, and
  `ProtocolV2PackfileResponse` expose `advance()` even though no caller resumes
  a partially completed response.

Accepted direction:

- Delete only `OutputSerialization` and the three external serializer classes
  in this migration slice, replacing them with direct writes and preserving
  existing byte-for-byte tests.
- Replace the three response/`advance()` shapes with one-shot `send...` methods
  that close their producer internally on success and failure.
- Do not opportunistically remove other output helpers. Reassess them during
  the post-implementation review.
- Keep structured wire-error kinds for server logging and diagnostics even if
  clients do not require a programmatic error classification.

### 4. One repository command repeatedly reconstructs invariant context

Status: accepted; implementation pending
Task: [Reuse one repository command context](../plans/upcoming-work/git-wire-architecture-simplification/repository-command-context/TASK.md)

Evidence:

- Operations on `GitNativeRepositoryService` repeatedly receive the same
  `InitialRequestData` and access hook.
- `DefaultGitNativeRepositoryService` repeatedly extracts the repository path
  and calls `findOrFail`, `fetchRepository`, or `receiveRepository` during one
  advertisement/negotiation/fetch or receive-pack command.
- Receive-pack can resolve the repository for advertisement, ingestion, and
  publication separately even though those stages belong to one command.

Accepted direction:

- After bootstrap, resolve or create one repository command context and reuse
  it through the command.
- Bind repository path, repository instance, access hook, and related helpers
  once, while retaining operation-specific fetch and ref-update authorization.
- Do not cache the context across sessions or across Smart HTTP discovery and
  POST requests.

### 5. `GitObjectId` does not own its own identity rules

Status: accepted; implementation pending
Task: [Make `GitObjectId` canonical](../plans/upcoming-work/git-wire-architecture-simplification/canonical-git-object-id/TASK.md)

Evidence:

- `GitObjectId` currently checks only for null and retains the caller's exact
  string spelling.
- Consumers duplicate 40-hex validation, lowercase conversion,
  case-insensitive comparison, and zero-ID constants.
- Record equality and hashing are therefore spelling-sensitive even though Git
  object identity is not.

Accepted direction:

- Validate the currently supported SHA-1 form and canonicalize hexadecimal
  spelling inside `GitObjectId`.
- Make typed equality/hash behavior and the zero object ID properties of the
  value type.
- Remove duplicate consumer logic where values are already typed, without
  adding SHA-256 abstraction or migrating unrelated client string APIs.

## Deferred Re-review Questions

After all five implementation tasks complete, verify:

1. Does `git-parser` have any production import or Maven path to native storage?
2. Does `git-client` still inherit server/storage implementation code?
3. Does every bootstrap initiator visibly compose wire, repository context, and
   the same immutable capability policy?
4. Is one repository instance resolved per command without weakening
   operation-specific authorization or resource ownership?
5. Is every advertised capability filtered once by canonical name and rejected
   consistently when hidden?
6. Are object IDs canonical immediately after their typed construction, with
   no typed-path normalization or validation duplicated by consumers?
7. Did direct blocking output remove the four serializer types and three
   `advance()` wrappers while preserving exact bytes and producer closure?
8. Which remaining output helpers, response models, or coordination concepts
   are now demonstrably removable?
9. Which structured error classifications are still used by logs,
   diagnostics, tests, or control flow?

## Post-implementation Results

Not evaluated yet. The follow-up review must replace this section with dated,
evidence-backed results for every finding and create separate task nodes for
any remaining work.
