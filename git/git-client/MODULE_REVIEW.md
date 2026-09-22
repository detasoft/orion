# Module Review: git/git-client

## 1. ASCII-only decoding and validation reject valid UTF-8 references

**Problem.** A remote advertising `refs/heads/ветка` fails discovery with MALFORMED_RESPONSE,
even when the caller wants another ASCII-named ref. Fetch and push read the same complete advertisement.
Constructing a push command for the Unicode ref also fails local validation.

**Sources.** [advertisement decoding](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L46),
[ASCII rejection](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L548),
[ref validation](src/main/java/pro/deta/orion/git/client/GitClientValidation.java#L32),
[push command model](src/main/java/pro/deta/orion/git/client/GitReceivePackRequest.java),
[client tests](src/test/java/pro/deta/orion/git/client/GitBlockingClientsTest.java),
and [advertisement tests](src/test/java/pro/deta/orion/git/client/GitRemoteAdvertisementTest.java).
Native proxy bootstrap discovers all refs before selecting the configured ref, so an unrelated Unicode
name is sufficient to prevent that real consumer from fetching.

**Documented behavior and contract.** Git's
[common protocol definitions](https://github.com/git/git/blob/master/Documentation/gitprotocol-common.adoc)
define refnames as octet strings subject to specific ref-format exclusions, not an ASCII-only alphabet.
UTF-8 branch and tag names are valid. No local requirement restricting remote refs to ASCII was found.
**Minimal repair.** Decode name-bearing text consistently with the existing UTF-8 writer and remove
blanket non-ASCII ref rejection, retaining actual Git ref restrictions. Cover discovery with mixed names,
annotated Unicode tags, push command bytes and matching status responses through the public client.
**Alternatives and consequences.** A byte-preserving public model would cover Git's broader arbitrary-octet
contract but is a larger change. UTF-8 support repairs the concrete case without claiming full arbitrary-byte
support. Skipping unfamiliar refs hides data and does not repair pushing those refs.
**Confidence.** High: explicit decoder and model predicates; no runtime reproduction in this audit.
**Priority signals.** Importance high because one unrelated legal ref blocks the remote operation;
repair ease medium because both decoding and validation must change together.
