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

## 2. Raw-pack fetch copies intermediate negotiation packets into the pack

**Problem.** A server advertises multi_ack_detailed without sideband. The client requests it and sends a
common have followed by done. For a legal response `ACK <id> common`, final `ACK <id>`, then raw PACK,
the client switches to raw copying after the first ACK and includes the final ACK pkt-line in the target.
A passive target can receive a success result for contaminated bytes; pack ingestion then fails.

**Sources.** [capability selection](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L66),
[early raw transition](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L109),
[raw copy](src/main/java/pro/deta/orion/git/client/GitBlockingClientWire.java#L127),
[fetch result](src/main/java/pro/deta/orion/git/client/GitUploadPackClient.java#L63),
[existing raw test](src/test/java/pro/deta/orion/git/client/GitBlockingClientsTest.java#L76).
The current raw test exercises only NAK with no detailed-ACK capability; proxy bootstrap's incremental
fetch supplies a have and ingests returned bytes.

**Documented behavior and contract.** Git's
[Packfile Negotiation specification](https://github.com/git/git/blob/master/Documentation/gitprotocol-pack.adoc#packfile-negotiation)
distinguishes intermediate ACK statuses from the terminal response before pack data.
[get_common_commits](https://github.com/git/git/blob/master/upload-pack.c) emits a common ACK when reading
a shared have and a final ACK on done. Sending done immediately does not eliminate the earlier ACK.
**Minimal repair.** Consume intermediate ACK statuses in the existing loop; start raw copying only after
terminal negotiation. Test multiple common/ready ACKs, a final ACK and exact raw-pack bytes/count,
while retaining the NAK path.
**Alternatives and consequences.** Not requesting detailed ACKs without sideband is smaller but reduces
negotiated functionality. Removing raw support would discard an existing supported transport path.
No separate negotiation abstraction is needed.
**Confidence.** High: client transition and reference producer agree; no runtime reproduction performed.
**Priority signals.** Importance high for correctness of this supported capability combination;
repair ease high, localized parsing and transition logic.
