# Module Review: net/git-transport — SSH administration

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
