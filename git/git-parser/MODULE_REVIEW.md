# Module Review: `git/git-parser`

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
