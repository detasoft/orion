# Module Review: `sshd-common`

## 1. An empty artifact forwards an already managed dependency

**Problem.** `core/common` requires an extra Orion artifact with no implementation, resources or independent
dependency policy. Its only manifest forwards upstream `org.apache.sshd:sshd-common`, already managed by root.

**Sources.** [Manifest](pom.xml), [root management](../../pom.xml#L127),
[sole artifact consumer](../../core/common/pom.xml#L45), [aggregator](../pom.xml#L19),
[BOM registration](../../bom/pom.xml#L129),
[key operations](../../core/common/src/main/java/pro/deta/orion/util/KeyUtils.java),
[trust decisions](../../core/common/src/main/java/pro/deta/orion/ssh/SshHostKeyDecision.java), and
[behavior coverage](../../core/common/src/test/java/pro/deta/orion/ssh/SshHostKeyDecisionTest.java).
Commit `f561fa86` replaced a direct Apache dependency with this empty module; it added no adapter.

**Documented behavior and contract.** No plan or documentation requires the wrapper coordinate. The
[SSH shell plan](../../docs/plans/tasks/08_interactive-ssh-shell/TASK.md) requires Apache Mina SSHD.
Preserve the upstream dependency's root-managed version and compile scope, key formats, fingerprints and
trust decisions. Consumers already use Apache types directly; the wrapper supplies no encapsulation.

**Minimal repair.** Replace the consumer's dependency group with `org.apache.sshd`; remove the wrapper from
the connectors aggregator and BOM; delete this module manifest and resolved report in the same change.
Keep upstream root management and meaningful behavior tests. Verify the graph through `make test`, not
POM-text assertions. No new concept is required.

**Alternatives and consequences.** Retention preserves needless publication structure. Changing packaging
to `pom` retains that structure; moving SSH code introduces a larger unsupported boundary change.
Removing Apache itself breaks real consumers. The neighboring BC bundle has actual shared version/exclusion
policy and should remain. Deleting the wrapper coordinate affects external users of that coordinate, but none
is documented or evidenced in this repository; application wire and persisted contracts remain unchanged.

**Confidence.** High from complete manifest, graph, creation history and callers; undocumented external
artifact users are unknown. No build or runtime check was run in this audit.

**Priority signals.** Importance: low, unnecessary reactor/artifact coupling. Repair ease: high, one
dependency substitution, two registration removals and module deletion without Java changes.
