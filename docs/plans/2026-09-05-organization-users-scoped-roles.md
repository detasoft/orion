# Organization Users and Scoped Roles Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add organization-local users and locally owned roles and grants at organization, team, and repository
scope to the immutable Orion document and deterministic XML v2 format.

**Architecture:** New immutable schema types model configuration scopes, qualified role and grant addresses,
users, credentials, roles, and grants. `OrionDocument` owns the nested definitions and validates the complete
reference graph, while version-specific JAXB DTOs and `OrionV2Mapper` remain the only XML boundary. The existing
system `AccessControl` remains the sole model for system administration and recovery. Runtime authentication and
access evaluation stay in their later tasks.

**Tech Stack:** Java 21 records and sealed types, JAXB, JUnit 5, AssertJ, Maven.

---

## Constraints

- Do not add compatibility constructors to `OrionDocument` hierarchy records. Update every in-repository caller
  to the new canonical constructors.
- Do not place organization users in `AccessControl`, derive them from system users, or add a second flattened
  registry.
- Keep v1 and current v2 system ACL reads unchanged. Existing v2 documents without new optional collections
  remain readable, while canonical writes emit the complete new shape.
- Membership never grants permissions. This task stores explicit role assignments and role composition only.
- This task stores `ALLOW` and `DENY`; it does not implement access-decision precedence or migrate
  `OrionAccessControlService`.
- Do not add plaintext reversible-secret credential types. Direct organization credentials are password
  verifiers and OpenSSH public keys only.
- Use ordinary loops instead of Java streams and keep source lines at or below 112 characters.

### Task 1: Define scoped identifiers and addresses

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RoleId.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/GrantId.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/ConfigurationScope.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RoleAddress.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/GrantAddress.java`
- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/ScopedIdentityTest.java`

**Step 1: Write failing identity tests**

Cover canonical role and grant ids, the three scope depths, parsing and rendering role/grant addresses,
malformed segment counts, and ancestor checks:

```java
ConfigurationScope organization = ConfigurationScope.organization(new OrganizationId("acme"));
ConfigurationScope team = ConfigurationScope.team(
        new OrganizationId("acme"), new TeamId("platform"));
ConfigurationScope repository = ConfigurationScope.repository(
        new RepositoryAddress(
                new OrganizationId("acme"),
                new TeamId("platform"),
                new RepositoryId("api")));

assertThat(new RoleAddress(organization, new RoleId("developer")).toString())
        .isEqualTo("acme/developer");
assertThat(RoleAddress.parse("acme/platform/api/maintainer").scope())
        .isEqualTo(repository);
assertThat(organization.isSameOrAncestorOf(repository)).isTrue();
assertThat(repository.isSameOrAncestorOf(team)).isFalse();
```

Also assert `RoleAddress` and `GrantAddress` are distinct types even for equal text and reject one or more than
four segments.

**Step 2: Run the focused test and record the red result**

Run outside the sandbox:

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.ScopedIdentityTest'
```

Expected: test compilation fails because the scoped identity types do not exist.

**Step 3: Implement the minimal value types**

Use `IdentifierRules.requireCanonical` in `RoleId` and `GrantId`. Implement `ConfigurationScope` as a record
with an organization id and optional team and repository ids. Reject a repository without a team. Provide only
these factories:

```java
public static ConfigurationScope organization(OrganizationId organizationId)
public static ConfigurationScope team(OrganizationId organizationId, TeamId teamId)
public static ConfigurationScope repository(RepositoryAddress address)
public static ConfigurationScope parse(String value)
public boolean isSameOrAncestorOf(ConfigurationScope other)
```

`RoleAddress` and `GrantAddress` each contain a non-null scope and local id, parse two through four canonical
segments, and render `scope + "/" + id`. Do not add a shared untyped address or stringly typed comparison
helper.

**Step 4: Run the focused test**

Run the command from Step 2. Expected: PASS.

**Step 5: Commit the identity slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion \
  core/schema/src/test/java/pro/deta/orion/schema/orion/ScopedIdentityTest.java
git commit -m "Define scoped role and grant identities"
```

### Task 2: Model organization users and credentials

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrganizationUser.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/UserCredential.java`
- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrganizationUserTest.java`

**Step 1: Write failing user-model tests**

Exercise enabled and disabled users, defensive copies, same user id in different owning organizations, team
memberships, role assignments, and credential ordering-independent value semantics. Use this public shape:

```java
OrganizationUser user = new OrganizationUser(
        new UserId("alice"),
        "Alice",
        "Operator",
        "alice@example.test",
        true,
        List.of(UserCredential.passwordVerifier(
                UserCredential.Type.ARGON2,
                "$argon2id$v=19$m=65536,t=3,p=1$...")),
        List.of(new TeamId("platform")),
        List.of(RoleAddress.parse("acme/developer")));
```

Assert blank credential values, duplicate credentials, unsupported direct secret types, duplicate memberships,
and duplicate role assignments fail. Cover a structurally valid canonical OpenSSH public key and invalid
type/base64 input without adding an SSH library dependency to `core/schema`.

**Step 2: Run the focused test and record the red result**

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.OrganizationUserTest'
```

Expected: test compilation fails because the user types do not exist.

**Step 3: Implement credentials and users**

`UserCredential` is an immutable value with `Type.ARGON2`, `Type.SHA1`, and `Type.OPENSSH_PUBLIC_KEY`, optional
`keyId`, and non-blank `value`. Factories distinguish password verifiers from public keys. For OpenSSH keys,
validate exactly an algorithm token and base64 payload, decode the payload, and reject trailing comments so the
stored form is canonical. Do not attempt cryptographic authentication here.

`OrganizationUser` copies and canonicalizes collections. Sort credentials by type, key id, and value; sort
memberships by team id and role assignments by address. Reject duplicates before copying. Profile fields may be
null, but ids and collection values may not.

**Step 4: Run the focused test**

Run the command from Step 2. Expected: PASS.

**Step 5: Commit the user-model slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion/OrganizationUser.java \
  core/schema/src/main/java/pro/deta/orion/schema/orion/UserCredential.java \
  core/schema/src/test/java/pro/deta/orion/schema/orion/OrganizationUserTest.java
git commit -m "Model organization-local users"
```

### Task 3: Model scoped roles, grants, and the complete graph

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/ScopedRole.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/ScopedGrant.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionDocument.java`
- Modify: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionDocumentTest.java`
- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionAuthorizationModelTest.java`
- Modify: in-repository Java callers constructing `OrionDocument.Organization`, `Team`, or `Repository`

**Step 1: Add failing local-definition tests**

Define these immutable values:

```java
ScopedGrant read = new ScopedGrant(
        new GrantId("read"),
        ScopedGrant.Effect.ALLOW,
        List.of(new AccessControl.GrantExpression(AccessControl.GrantKey.READ, "true")));
ScopedRole developer = new ScopedRole(
        new RoleId("developer"),
        List.of(),
        List.of(GrantAddress.parse("acme/read")));
```

Extend hierarchy construction so organization owns users/grants/roles/teams, team owns
grants/roles/repositories, and repository owns grants/roles after its existing remote configuration. Cover equal
local ids in separate scopes and duplicate ids in one scope.

**Step 2: Add failing complete-document validation tests**

Cover:

- a user membership naming a missing team;
- a user role assignment outside its organization;
- a missing role assignment;
- role references to same-scope and ancestor roles/grants;
- forbidden role references to descendants and another organization;
- missing role and grant references;
- direct and multi-role cycles;
- membership remaining independent from role assignment;
- `ALLOW` and `DENY` grants coexisting without evaluation.

**Step 3: Run the focused tests and record the red result**

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.OrionDocumentTest,pro.deta.orion.schema.orion.OrionAuthorizationModelTest'
```

Expected: compilation fails for the new model and constructor shapes.

**Step 4: Implement role and grant values**

`ScopedGrant` copies expressions, requires an id and effect, and preserves expression order because the mapper
owns wire canonicalization. `ScopedRole` copies role and grant references and rejects duplicates.

Replace the hierarchy record constructors atomically with:

```java
Organization(
        OrganizationId id,
        String displayName,
        List<OrganizationUser> users,
        List<ScopedGrant> grants,
        List<ScopedRole> roles,
        List<Team> teams)

Team(
        TeamId id,
        String displayName,
        List<ScopedGrant> grants,
        List<ScopedRole> roles,
        List<Repository> repositories)

Repository(
        RepositoryId id,
        String displayName,
        String defaultBranch,
        RepositoryPolicy policy,
        List<RepositoryRemote> remotes,
        List<ScopedGrant> grants,
        List<ScopedRole> roles)
```

Each owner rejects duplicate local definition ids. Update all real and test constructors directly; add no
old-shape overloads.

**Step 5: Implement one document graph validator**

After defensive copying, `OrionDocument` builds maps keyed by typed role and grant addresses. Validate user
memberships and assignments, then role references and scope direction. Perform depth-first role traversal with
unvisited/visiting/visited states and report the address that closes a cycle. Use ordinary loops and keep this
validator package-private unless a production caller requires it.

**Step 6: Run the focused schema tests**

Run the command from Step 3. Expected: PASS.

**Step 7: Commit the domain graph slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion \
  core/schema/src/test/java/pro/deta/orion/schema/orion
git commit -m "Validate organization users and scoped definitions"
```

### Task 4: Extend the isolated JAXB v2 wire model

**Files:**

- Modify: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2.java`
- Modify: `core/schema/src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java`

**Step 1: Add failing DTO-boundary tests**

Add organization users and definitions at all three scopes to a DTO. Assert new wrappers are optional on input
so the existing minimal v2 DTO still maps, while getters return the supplied wire data. Keep system ACL DTOs
unchanged.

Use distinct DTO classes named `OrganizationUser`, `OrganizationCredential`, `ScopedRole`, `ScopedGrant`, and
`ScopedGrantExpression`; do not reuse `OrionV2.User`, `Role`, or `Grant` from system ACL.

**Step 2: Run the mapper test and record the red result**

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.v2.OrionV2MapperTest'
```

Expected: test compilation fails because the v2 DTO does not expose the new collections.

**Step 3: Implement the version-specific DTO shape**

Set property order to:

- organization: display name, users, grants, roles, teams;
- team: display name, grants, roles, repositories;
- repository: display name, default branch, policy, remotes, grants, roles;
- organization user: first, last, email, credentials, memberships, roles;
- scoped role: role references, grant references;
- scoped grant: expressions.

Use `enabled` as a required user attribute in canonical output, `effect` as a required scoped-grant attribute,
and local ids as required attributes. Reference values are canonical address text. New wrappers are not
schema-required for read compatibility with already persisted v2 documents.

**Step 4: Run the mapper test**

Run the command from Step 2. Expected: existing and new DTO tests PASS.

**Step 5: Commit the wire-model slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2.java \
  core/schema/src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java
git commit -m "Extend the Orion v2 identity wire model"
```

### Task 5: Map users and scoped definitions canonically

**Files:**

- Modify: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java`
- Modify: `core/schema/src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java`

**Step 1: Add failing round-trip and ordering tests**

Build a domain document containing two organizations with equal user and role ids, definitions at every scope,
enabled and disabled users, credentials, memberships, role composition, `ALLOW`, and `DENY`. Assert exact domain
round trip.

Build equivalent documents with reversed users, credentials, memberships, roles, grants, references, and grant
expressions. Assert `fromCurrent` returns every collection in canonical identifier/address order.

**Step 2: Add failing invalid-wire tests**

Assert precise failures for duplicate local ids, invalid credential values, missing targets, escaping
references, and cycles after mapping into the complete domain document.

**Step 3: Run the mapper test and record the red result**

Run the command from Task 4 Step 2. Expected: new assertions FAIL because mapper ignores the new fields.

**Step 4: Implement both mapping directions**

Map wire collections to new immutable types with empty-list defaults. Create typed addresses from canonical
strings. Map scoped grant expressions through the existing grant key enum without converting scoped definitions
into system ACL objects.

When mapping out, sort every new collection and reference list. Use stable null-safe comparators only at the
wire input boundary; domain output contains no null identifiers.

**Step 5: Run the mapper tests**

Run the command from Task 4 Step 2. Expected: PASS.

**Step 6: Commit the mapper slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java \
  core/schema/src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java
git commit -m "Map scoped Orion identities deterministically"
```

### Task 6: Verify the strict XML contract

**Files:**

- Modify: `core/schema/src/test/resources/pro/deta/orion/schema/orion/orion-v2.xml`
- Modify: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionXmlTest.java`
- Modify: `docs/plans/2026-09-03-hierarchical-orion-xml-v2-design.md`

**Step 1: Extend the checked-in v2 fixture**

Add two organization users, organization definitions, team definitions, and repository definitions. Include one
disabled user, a team membership, an organization role assignment, an ancestor role composition reference, and a
repository-local deny grant. Do not change the system ACL fixture semantics or existing repository remote.

**Step 2: Add failing XML contract tests**

Assert the extended fixture round-trips exactly, generated XSD contains every new wrapper/attribute, legacy v1
still maps only to system ACL, and old minimal v2 without new wrappers remains readable. Add reader failures for
unknown new fields and semantic cross-scope/cycle errors.

**Step 3: Run the XML tests and record the red result**

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.OrionXmlTest'
```

Expected: fixture assertions FAIL until the mapper and strict schema expose the complete shape.

**Step 4: Complete XML mapping and documentation**

Make only corrections required by generated-schema validation and deterministic output. Update the original v2
design to state that its deferred organization-user and scoped-definition slices are now present, linking to
`2026-09-05-organization-users-scoped-roles-design.md`. Do not describe runtime activation as complete.

**Step 5: Run all focused schema tests**

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.ScopedIdentityTest,\
pro.deta.orion.schema.orion.OrganizationUserTest,\
pro.deta.orion.schema.orion.OrionDocumentTest,\
pro.deta.orion.schema.orion.OrionAuthorizationModelTest,\
pro.deta.orion.schema.orion.v2.OrionV2MapperTest,\
pro.deta.orion.schema.orion.OrionXmlTest'
```

Expected: PASS.

**Step 6: Commit the XML contract slice**

```bash
git add core/schema/src/test docs/plans/2026-09-03-hierarchical-orion-xml-v2-design.md
git commit -m "Publish organization users and scoped roles in Orion XML"
```

### Task 7: Verify and prepare the task branch

**Files:**

- Verify: `core/schema/src/main/java/pro/deta/orion/schema/orion/`
- Verify: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/`
- Verify: `core/schema/src/test/`
- Verify: `docs/plans/`

**Step 1: Search for accidental parallel paths**

```bash
rg -n "new OrionDocument\\.(Organization|Team|Repository)" --glob '*.java'
rg -n "organization.*AccessControl|AccessControl.*organization" core/schema --glob '*.java'
rg -n "class .*RoleAddress|record .*RoleAddress|class .*GrantAddress|record .*GrantAddress" \
  core/schema/src/main/java
```

Confirm every hierarchy caller uses the new constructor, organization users are absent from system ACL mapping,
and there is exactly one production type for each scoped address.

**Step 2: Run formatting checks**

```bash
git diff --check
find core/schema/src -name '*.java' -print0 | \
  xargs -0 awk 'length($0) > 112 { print FILENAME ":" FNR ":" length($0) }'
```

Expected: no output.

**Step 3: Run routine development verification outside the sandbox**

```bash
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 4: Commit any task-caused verification fixes**

Use the exact same subject as the commit that introduced the issue so final branch squashing remains
straightforward. Do not modify unrelated working-tree changes.

**Step 5: Request code review**

Use `superpowers:requesting-code-review` against the task branch. Apply `docs/reviews/RULES.md` and address all
blocking findings before completion.

**Step 6: Finish the dedicated worktree**

Use `superpowers:finishing-a-development-branch`. Squash all task-branch commits, remove both completed task
directories and their parent links in the squashed commit, cherry-pick the result to `main`, run the required
post-commit `make test` on `main`, then remove the worktree and branch. Do not mark either task complete before
the transfer and cleanup are verified.
