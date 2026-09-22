# Module Review: `git/git-parser`

## 12. Incoming pack version 3 is rejected

**Problem.** A correctly checksummed SHA-1 pack with header version 3 fails before object ingestion.
Completion contains a second version-2-only check.
**Sources.** [PackIngestor](src/main/java/pro/deta/orion/git/parser/v2/pack/PackIngestor.java#L46),
[resolver header check](src/main/java/pro/deta/orion/git/parser/v2/pack/GitPackObjectResolver.java#L187),
[PushCommand](src/main/java/pro/deta/orion/git/parser/v2/command/PushCommand.java),
[header tests](src/test/java/pro/deta/orion/git/parser/v2/pack/PackIngestorTest.java#L174).
The existing modified-version fixture does not recompute its trailer and is not a valid version-3 example.
**Documented behavior and contract.** The
[pack format](https://github.com/git/git/blob/master/Documentation/gitformat-pack.adoc) and
[Git version predicate](https://github.com/git/git/blob/master/pack.h) accept versions 2 and 3;
Git normally generates version 2.
**Minimal repair.** Accept both versions in ingestion and completion, retaining version-2 output.
Test a nonempty version-3 pack with valid trailer through resolution/publication and retain unsupported-version rejection.
**Alternatives and consequences.** A separate decoder is unnecessary. Changing only the ingestor leaves
the later rejection. Documenting a restricted subset would not resolve this interoperability limitation.
**Confidence.** High on explicit predicates and upstream support; occurrence frequency not measured.
**Priority signals.** Importance medium for valid but uncommon packs; repair ease high, two local checks.

## 13. Advertised server-option rejects legal values containing spaces

**Problem.** A v2 header `server-option=foo bar` fails before dispatch despite server-option advertisement.
**Sources.** [advertisement and header parser](src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java#L131),
[generic value validation](src/main/java/pro/deta/orion/git/parser/v2/capability/GitCapabilityValue.java),
[existing server-option test](../../net/git-transport/src/test/java/pro/deta/orion/transport/git/GitBlockingWireSessionTest.java#L298).
The test uses only `server-option=trace`; generic validation rejects whitespace before recognizing the option.
**Documented behavior and contract.** Git's
[server-option specification](https://github.com/git/git/blob/master/Documentation/gitprotocol-v2.adoc#server-option)
excludes NUL/LF in option payloads; a space is not a capability separator inside this pkt-line.
**Minimal repair.** Parse the option under its own header-payload rule before generic token validation.
Cover spaced/repeated options and invalid bytes through command dispatch; preserve advertised-feature checks.
**Alternatives and consequences.** Globally allowing spaces weakens unrelated legacy capability rules.
No new option service or public abstraction is necessary; unknown-option semantics can remain unchanged.
**Confidence.** High for spaced values, without assuming a meaning for any server-specific option.
**Priority signals.** Importance medium for a legal advertised-feature request; repair ease high and local.
