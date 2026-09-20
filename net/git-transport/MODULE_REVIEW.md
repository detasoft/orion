# Module Review: net/git-transport — SSH administration

## 2. Audit fields can inject control characters into log framing

**Problem.** An authenticated exec command such as `/auth/key rm 'SHA256:bogus<LF>Orion command audit user=root ...'`,
with an actual quoted newline, reaches audit formatting with that newline intact, even if execution rejects
the fingerprint. Concatenating the parameter map inserts attacker-controlled physical lines into the message.

**Sources.** [Unescaped formatting](src/main/java/pro/deta/orion/transport/git/command/Slf4jCommandAuditSink.java#L20),
[production audit wiring](src/main/java/pro/deta/orion/transport/git/command/SshCommandModule.java#L89),
[quoted characters](../../core/command/src/main/java/pro/deta/orion/command/CommandLineParser.java#L181),
[audit parameter extraction](../../core/command/src/main/java/pro/deta/orion/command/DefaultCommandDispatcher.java#L55),
and [redaction-only sink test](src/test/java/pro/deta/orion/transport/git/command/Slf4jCommandAuditSinkTest.java#L13).

**Documented behavior.** [The SSH plan](../../docs/plans/tasks/08_interactive-ssh-shell/TASK.md) requires command
and credential auditing with sensitive-value redaction; exact serialization is not specified.

**Contract.** Untrusted field values must remain distinguishable from audit record framing. Preserve existing
redaction and record content; downstream collector behavior is not assumed by this finding.

**Minimal repair.** Escape control characters in untrusted fields, including map keys/values, at the existing
sink boundary. Reuse existing escaping/serialization facilities. Test LF, CR and other controls alongside
redaction through observable formatted output.

**Alternatives and consequences.** Parser restrictions remove valid argument data and miss other fields.
Structured serialization is valid but changes log format more broadly than local escaping. No new audit
service is needed; sensitive values must be removed before either serialization approach.

**Confidence.** High in raw control characters reaching the message; downstream rendering was not inspected.

**Priority signals.** Importance: medium for audit integrity. Repair ease: high through local formatting.

## 3. An SSH adapter test asserts production source text instead of behavior

**Problem.** protocolErrorHelpersDoNotCreateBlockingWireTransport reads SshCommandFactory.java, slices by method
names and rejects a constructor substring. Harmless rearrangement can fail it, while indirect construction
can pass it despite retaining the unwanted behavior.

**Sources.** [Source-text test](src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java#L455),
[neighboring packet behavior](src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java#L430),
and [large error splitting](src/test/java/pro/deta/orion/transport/git/ssh/SshCommandFactoryTest.java#L466).

**Documented behavior.** Repository test-quality rules require observable verification and explicitly prohibit
source/build/configuration text assertions. The neighboring tests already invoke error encoding behavior.

**Contract.** Verify protocol output and any relevant transport side effects through invocation, not source
spelling. Preserve meaningful packet coverage.

**Minimal repair.** Delete the source-text test. If an uncovered transport effect matters, add a behavioral
observation of the helper's input/output interactions through its existing interface.

**Alternatives and consequences.** Another substring or syntax-tree assertion retains the wrong test boundary.
Deleting this test changes no production behavior and introduces no concept or dependency.

**Confidence.** High, direct test-quality rule violation. No excluded Git implementation was inspected.

**Priority signals.** Importance: low runtime impact but required policy correction. Repair ease: high, isolated
deletion while retaining behavioral tests.
