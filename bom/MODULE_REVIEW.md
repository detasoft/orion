# Module Review: `bom`

## 1. The internal artifact catalog omits five produced JARs

**Problem.** The BOM omits `agentd`, `antlr-parser`, `resolvers`, `git-sync`, and
`git-engine-orion-adapters`. Dependencies on the first, second and fifth already compensate through separate
version declarations. A dependency without a version cannot obtain its version from this central catalog.

**Sources.** [Catalog](pom.xml), producers [AgentD](../agentd/pom.xml),
[parser](../core/parent-reference/antlr-parser/pom.xml),
[resolvers](../core/parent-reference/resolvers/pom.xml), [sync](../git/git-sync/pom.xml), and
[adapters](../tests/git-engine-orion-adapters/pom.xml).
Separate declarations exist in [session server](../agent-session-server/pom.xml),
[provisioning](../agent-provisioning/pom.xml), [HTTP](../net/http-core/pom.xml),
[matrix](../tests/git-engine-interoperability-matrix/pom.xml), and
[reference parent](../core/parent-reference/pom.xml).
Actual consumers include `SessionJournalRelayLivePeerTest`, `RemoteAgentdProvisionerTest`,
`AgentSessionAcceptanceIT`, `GitMatrixDefinition` and the production `ResourceReferenceParser`.

**Documented behavior and contract.** The previously accepted report states that the BOM is internal,
contains every produced project artifact, and includes shared test artifacts. No independent definition was
found. These five JAR omissions violate that accepted catalog contract regardless of the treatment of POMs.
No current build failure is established: local declarations compensate, and two JARs have no dependent module.

**Minimal repair.** Add five entries using the existing project version. Remove the four compensated versions
for AgentD/adapters and the redundant local parser/reference management block. Preserve scopes and versions.
Verify real Maven resolution and the reactor; do not add POM-text assertions.

**Alternatives and consequences.** Keeping separate catalogs requires changing the accepted central-ownership
contract. A separate test BOM or generated catalog adds unnecessary mechanisms.

**Confidence.** High from all reactor and catalog coordinates; the effective graph and publication were not run.

**Priority signals.** Importance: medium, central ownership is incomplete. Repair ease: high, a few POM edits
through an existing mechanism, without production or persisted changes.
