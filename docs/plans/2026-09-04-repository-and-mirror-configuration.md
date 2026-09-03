# Repository and Mirror Configuration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extend Orion XML v2 with immutable repository policy and provider-neutral remote definitions needed by primary upstream synchronization.

**Architecture:** Keep desired repository and remote configuration in `core/schema` and operational synchronization state outside the XML model. Add small validated value types for aliases, refs, secret references, roles, triggers, mappings, and update policy; map them deterministically through the existing JAXB v2 DTO without introducing a Git runtime dependency into schema.

**Tech Stack:** Java 21 records and enums, JAXB, JUnit 5, AssertJ, Maven.

---

### Task 1: Define the immutable repository configuration model

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RemoteAlias.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RemoteRole.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RemoteProvider.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RemoteTrigger.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RemoteRefMapping.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RemoteUpdatePolicy.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/ConfigurationSecretReference.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RepositoryPolicy.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RepositoryRemote.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionDocument.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java`
- Modify: existing `core/schema/src/test/java/pro/deta/orion/schema/orion/` construction sites
- Test: `core/schema/src/test/java/pro/deta/orion/schema/orion/RepositoryConfigurationTest.java`

**Step 1: Write failing domain tests**

Add tests that construct a repository with safe defaults and a primary remote:

```java
@Test
void acceptsOneReservedPrimaryUpstream() {
    RepositoryRemote upstream = new RepositoryRemote(
            new RemoteAlias("upstream"),
            RemoteRole.PRIMARY,
            RemoteProvider.GITHUB,
            URI.create("https://github.com/acme/project.git"),
            new ConfigurationSecretReference(
                    ConfigurationSecretReference.Scope.REPOSITORY,
                    "github-token"),
            Set.of(
                    RemoteTrigger.STARTUP_RECONCILE,
                    RemoteTrigger.LOCAL_REF_UPDATE,
                    RemoteTrigger.PERIODIC_AUDIT),
            List.of(RemoteRefMapping.allBranches()),
            RemoteUpdatePolicy.fastForwardOnly());

    OrionDocument.Repository repository = new OrionDocument.Repository(
            new RepositoryId("project"),
            "Project",
            "refs/heads/main",
            RepositoryPolicy.safeDefaults(),
            List.of(upstream));

    assertThat(repository.remotes()).containsExactly(upstream);
}
```

Cover these non-trivial cases:

- duplicate aliases;
- more than one `PRIMARY` remote;
- `PRIMARY` with an alias other than `upstream`;
- `upstream` with a role other than `PRIMARY`;
- URI user-info, fragment, unsupported scheme, or missing host;
- invalid default branch and ref mappings;
- wildcard source/destination mismatch;
- blank or escaping secret identifiers;
- defensive copies of remotes, triggers, and mappings.

**Step 2: Run the tests and confirm failure**

Run:

```bash
make run-test MODULE=core/schema TEST='RepositoryConfigurationTest'
```

Expected: compilation failure because the repository configuration types do not exist.

**Step 3: Implement the validated model**

Use these semantics:

- `RemoteAlias` uses the existing canonical lowercase identifier grammar.
- `RemoteRole` contains `PRIMARY` and `OUTBOUND_ONLY`.
- `RemoteProvider` contains `GENERIC` and `GITHUB`; provider-specific host validation remains outside schema.
- `RemoteTrigger` contains `STARTUP_RECONCILE`, `LOCAL_REF_UPDATE`, `PERIODIC_AUDIT`, and `MANUAL_RETRY`.
- `ConfigurationSecretReference.Scope` contains `ORGANIZATION` and `REPOSITORY`.
- the first delivery accepts only `https` remote URIs, requires a host, and
  rejects user-info, query, and fragment data; the SSH slice will extend this
  contract with a non-secret SSH user.
- `RemoteRefMapping` accepts canonical full refs under `refs/heads/` or `refs/tags/`; matching `*` wildcards are allowed.
- `RemoteUpdatePolicy.fastForwardOnly()` disables force, deletes, and tag rewrites.
- `RepositoryPolicy.safeDefaults()` disables force, deletes, and tag rewrites.
- a repository defaults to `refs/heads/main`, safe policy, and no remotes when translated from legacy configuration.
- remote aliases are unique; at most one primary exists; `PRIMARY` and `upstream` imply each other.

Replace the current two-field repository record directly:

```java
public record Repository(
        RepositoryId id,
        String displayName,
        String defaultBranch,
        RepositoryPolicy policy,
        List<RepositoryRemote> remotes) {
}
```

Update existing construction sites rather than retaining a compatibility constructor.
Until Task 2 adds the wire fields, make `OrionV2Mapper` supply domain defaults
when reading a repository and omit those new values when writing the existing
two-field wire DTO.

**Step 4: Run the focused tests**

Run:

```bash
make run-test MODULE=core/schema TEST='RepositoryConfigurationTest,OrionDocumentTest,OrionHierarchyIdentityTest'
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion \
  core/schema/src/test/java/pro/deta/orion/schema/orion
git commit -m "Define repository and remote configuration"
```

### Task 2: Extend the Orion XML v2 wire model

**Files:**

- Modify: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java`
- Modify: `core/schema/src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java`

**Step 1: Write failing mapper tests**

Add a round-trip test with one primary GitHub HTTPS remote and assert every field:

```java
@Test
void mapsRepositoryPolicyAndPrimaryRemoteBothWays() {
    OrionDocument source = documentWithPrimaryUpstream();

    OrionV2 wire = OrionV2Mapper.fromCurrent(source);
    OrionDocument restored = OrionV2Mapper.toCurrent(wire);

    assertThat(restored).isEqualTo(source);
    OrionV2.Remote remote = wire.getOrganizations().getFirst()
            .getTeams().getFirst().getRepositories().getFirst()
            .getRemotes().getFirst();
    assertThat(remote.getAlias()).isEqualTo("upstream");
    assertThat(remote.getRole()).isEqualTo(OrionV2.RemoteRole.PRIMARY);
}
```

Also test deterministic ordering of remotes, triggers, and ref mappings, plus defaulting when a v2 repository omits every new optional field.

**Step 2: Run the mapper tests and confirm failure**

Run:

```bash
make run-test MODULE=core/schema TEST='OrionV2MapperTest'
```

Expected: compilation failure because JAXB repository configuration fields do not exist.

**Step 3: Add JAXB DTOs and deterministic mapping**

Extend `OrionV2.Repository` with:

- `defaultBranch`;
- `policy`;
- a `remotes` wrapper containing `remote` elements.

Add nested DTOs/enums for repository policy, remote, credential reference,
triggers, ref mappings, and update policy. Serialize enum names explicitly and
keep a stable `@XmlType(propOrder = ...)` for every DTO.

On read, omitted fields use the domain defaults. On write, emit explicit
defaults and empty wrappers so equivalent documents serialize identically.
Sort remotes by alias, triggers by enum name, and mappings by source then
destination before constructing JAXB lists.

**Step 4: Run the mapper tests**

Run:

```bash
make run-test MODULE=core/schema TEST='OrionV2MapperTest'
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion/v2 \
  core/schema/src/test/java/pro/deta/orion/schema/orion/v2
git commit -m "Map repository remotes through Orion XML v2"
```

### Task 3: Verify XML validation and deterministic round trips

**Files:**

- Modify: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionXmlTest.java`
- Modify: `core/schema/src/test/resources/pro/deta/orion/schema/orion/orion-v2.xml`

**Step 1: Add failing XML acceptance tests**

Extend the checked-in v2 fixture with a primary `upstream` remote. Assert that:

- the generated schema contains repository policy and remote elements;
- the fixture validates and round-trips;
- serialized output never contains an inline token or URI user-info;
- duplicate aliases and a non-primary `upstream` fail on read;
- an existing minimal v2 repository without the new elements still reads with safe defaults;
- equivalent remote and trigger input order produces identical XML.

**Step 2: Run the XML tests and confirm failure**

Run:

```bash
make run-test MODULE=core/schema TEST='OrionXmlTest'
```

Expected: FAIL until the fixture, schema assertions, and mapper behavior agree.

**Step 3: Complete the fixture and mapping corrections**

Use a reference-only credential representation such as:

```xml
<credential>
  <scope>REPOSITORY</scope>
  <reference>github-token</reference>
</credential>
```

Do not place a token, serialized encrypted envelope, runtime state, queue
metadata, or last-sync information in the fixture.

**Step 4: Run all schema tests**

Run:

```bash
make run-test MODULE=core/schema TEST='*Test'
```

Expected: PASS.

**Step 5: Run development verification**

Run:

```bash
mvn verify -Pdev -T 4
```

Expected: every module passes except the separately tracked pre-existing
`MinaSshOperationTest#wholeOperationWatchdogClosesAStalledSession` baseline
failure if it remains unresolved.

**Step 6: Commit verification corrections**

```bash
git add core/schema
git commit -m "Verify repository remote XML configuration"
```

### Task 4: Prepare the synchronization implementation slice

**Files:**

- Modify: `docs/plans/current-work/hierarchical-orion-configuration/TASK.md`
- Delete after completion: `docs/plans/current-work/hierarchical-orion-configuration/repository-and-mirror-configuration/`
- Move: `docs/plans/upcoming-work/external-git-repository-sync/` to `docs/plans/current-work/external-git-repository-sync/`
- Create: `docs/plans/current-work/external-git-repository-sync/primary-upstream/TASK.md`
- Modify: `docs/plans/current-work/TASK.md`
- Modify: `docs/plans/upcoming-work/TASK.md`

**Step 1: Verify the configuration completion boundary**

Confirm the final model contains repository identity, default branch, safe
policy, provider-neutral remotes, role, mapping, triggers, credential scope,
and remote update policy while excluding all runtime synchronization state.

**Step 2: Update the task tree for the next leaf**

Complete the dedicated-worktree leaf cleanup required by `AGENTS.md`. Move the
external synchronization parent to current work, add the approved primary
upstream implementation leaf, and retain branch filtering as a later child.
The primary-upstream leaf depends on this completed configuration task.

**Step 3: Finish through the dedicated-worktree workflow**

Squash all repository-configuration commits into one logical commit, delete
the completed leaf task directory and parent link in that squash, cherry-pick
the result to `main`, run `make test` on `main`, and remove this worktree and
branch only after transfer and cleanup are verified.

Expected squashed subject:

```text
Add repository and mirror configuration [task: hierarchical-orion-configuration/repository-and-mirror-configuration]
```
