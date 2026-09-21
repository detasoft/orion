# Module Review: net/git-transport

## 4. SSH receive-pack sends sideband errors before negotiation

**Problem.** An authenticated push fails while opening its repository, for example on write/create access
rejection. Before any ref advertisement or client capability selection, the SSH catch block emits a
pkt-line beginning with sideband byte 3 and a stack trace. The client is still expecting advertisement
records or an initial protocol error.

**Sources.** [outer SSH catch](src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java#L294),
[command-name-based error format](src/main/java/pro/deta/orion/transport/git/ssh/SshCommandFactory.java#L322),
[repository opening before advertisement](../../git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireSession.java#L49),
[access and provider failures](src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java#L35),
and [helper tests](src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java#L464).
The tests explicitly expect unconditional sideband stack-trace formatting.

**Documented behavior and contract.** Git's
[pack protocol](https://github.com/git/git/blob/master/Documentation/gitprotocol-pack.adoc)
defines initial ref/ERR framing; its
[capability specification](https://github.com/git/git/blob/master/Documentation/gitprotocol-capabilities.adoc#side-band-side-band-64k)
makes multiplexing negotiated. Merely invoking receive-pack does not select sideband.
**Minimal repair.** Send uncaught outer transport failures through existing SSH stderr with nonzero exit;
leave protocol-stage formatting with the existing protocol owner. Test an actual command with a denied
repository open, not only the formatting helper.
**Alternatives and consequences.** An initial ERR packet also works before advertisement. Preserving
sideband diagnostics later requires formatting where negotiated capability and phase are known;
duplicating that state in the outer transport is unnecessary. Cover failures with and without negotiated
sideband and preserve useful denial diagnostics.
**Confidence.** High: direct failure path and exact-byte test; no live SSH reproduction was run.
**Priority signals.** Importance medium, because routine rejected pushes become malformed protocol
responses; repair ease high when using existing stderr/exit, medium if retaining negotiated wire diagnostics.
