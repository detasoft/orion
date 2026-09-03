# Hierarchical Orion XML v2 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Introduce stable hierarchical identities and a strict, deterministic Orion XML v2 document while reading
legacy ACL v1 documents explicitly.

**Architecture:** Immutable current-domain types model identifiers, addresses, the hierarchy, and the system ACL.
Version-specific mutable JAXB DTOs and translators own wire compatibility. `OrionXml` securely dispatches v1 or v2
reads, validates v2 against a generated XSD, and writes only canonical v2 XML.

**Tech Stack:** Java 21, JAXB, generated W3C XML Schema, JUnit 5, AssertJ, Maven.

---

### Task 1: Add canonical hierarchy identifiers and addresses

**Files:**

- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionHierarchyIdentityTest.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/IdentifierRules.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrganizationId.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/TeamId.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RepositoryId.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/UserId.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RepositoryAddress.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/PrincipalAddress.java`

**Step 1: Write the failing identity tests**

Cover canonical values, `acme/platform/api`, `system/root`, `acme/alice`, exact segment counts, the reserved
organization id `system`, uppercase, whitespace, repeated or edge separators, dot segments, slash, backslash, and
cross-organization qualification.

The public contract exercised by the tests is:

```java
OrganizationId organization = new OrganizationId("acme");
RepositoryAddress address = RepositoryAddress.parse("acme/platform/api");
PrincipalAddress root = PrincipalAddress.parse("system/root");
PrincipalAddress alice = PrincipalAddress.parse("acme/alice");

assertThat(address.toString()).isEqualTo("acme/platform/api");
assertThat(root.isSystem()).isTrue();
assertThat(alice.requireOrganization(organization)).isSameAs(alice);
```

**Step 2: Verify that the identity tests fail**

Run:

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.OrionHierarchyIdentityTest'
```

Expected: compilation fails because the hierarchy identity types do not exist.

**Step 3: Implement the minimal immutable identity types**

Use records. Centralize validation in package-private `IdentifierRules.requireCanonical(String, String)` with the
pattern `[a-z0-9]+(?:[._-][a-z0-9]+)*`. Reject rather than normalize non-canonical input. `OrganizationId` also
rejects `system` because that segment identifies system principals.

`RepositoryAddress.parse` accepts exactly three segments. `PrincipalAddress` is a sealed interface with nested
system and organization implementations, a two-segment parser, canonical `toString`, `isSystem`, and
`requireOrganization`.

**Step 4: Run the focused identity tests**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit the identity slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion \
  core/schema/src/test/java/pro/deta/orion/schema/orion/OrionHierarchyIdentityTest.java
git commit -m "Define hierarchical Orion identities"
```

### Task 2: Add the immutable Orion document model

**Files:**

- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionDocumentTest.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionDocument.java`

**Step 1: Write the failing document tests**

Construct this model through immutable nested values:

```java
OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("api"), "API");
OrionDocument.Team team = new OrionDocument.Team(new TeamId("platform"), "Platform", List.of(repository));
OrionDocument.Organization organization = new OrionDocument.Organization(
        new OrganizationId("acme"), "Acme", List.of(team));
OrionDocument document = new OrionDocument(
        new OrionDocument.SystemConfiguration(accessControl),
        List.of(organization));
```

Assert defensive copies, `repositoryAddress` ownership, mutable display names being independent from ids, and
duplicate organization, team, and repository rejection. Include empty-system and empty-organization happy paths.

**Step 2: Verify that the document tests fail**

Run:

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.OrionDocumentTest'
```

Expected: compilation fails because `OrionDocument` does not exist.

**Step 3: Implement the immutable document**

`OrionDocument` owns a non-null `SystemConfiguration` and copied organization list. Nested immutable classes own
organization, team, and repository ids, optional display names, and copied child lists. Constructors reject null
nodes and duplicate ids in their immediate scope. A repository address is derived only with the containing
organization and team ids, so a repository cannot exist at a canonical address without both owners.

`SystemConfiguration` wraps the existing immutable `AccessControl`; null becomes an empty ACL only at explicit
factory boundaries, not silently inside arbitrary hierarchy nodes.

**Step 4: Run the focused document tests**

Run the command from Step 2.

Expected: PASS.

**Step 5: Commit the document slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion/OrionDocument.java \
  core/schema/src/test/java/pro/deta/orion/schema/orion/OrionDocumentTest.java
git commit -m "Model the immutable Orion configuration document"
```

### Task 3: Add isolated JAXB v2 DTOs and canonical mapping

**Files:**

- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/v2/OrionV2MapperTest.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/v2/OrionV2Mapper.java`

**Step 1: Write failing mapper boundary tests**

Assert that `OrionDocument` has no JAXB annotations, `OrionV2` has `@XmlRootElement(name = "orion")`, and mapping
round-trips a system ACL plus two organizations with teams and repositories. Create equivalent domain documents in
different list orders and assert that `OrionV2Mapper.fromCurrent` returns id-sorted DTO collections.

Add v2 DTO inputs with duplicate organization, team, repository, ACL user, role, and grant ids and assert precise
`IllegalArgumentException` messages.

**Step 2: Verify that the mapper tests fail**

Run:

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.v2.OrionV2MapperTest'
```

Expected: compilation fails because the v2 DTO and mapper do not exist.

**Step 3: Implement the v2 wire model**

Create one JAXB root DTO with nested `SystemConfiguration`, `Organization`, `Team`, `Repository`, and ACL wire
types. Use field access, explicit `@XmlType(propOrder = ...)`, singular collection items, required root/system/
organization wrappers, and a required `schemaVersion` enum containing only `2`.

Do not reuse `AccessControlV1` inside v2. Mirror the current ACL fields in v2-specific nested DTOs so later schema
versions can evolve independently.

**Step 4: Implement the mapper and stable ordering**

Map every DTO into immutable current-domain values. Let domain constructors validate hierarchy duplicates and add
mapper checks for duplicate ACL ids. When mapping out, copy and sort organizations, teams, repositories, users,
roles, grants, role/grant references, credentials, nested grants, and expressions using stable null-safe keys.

Do not use streams for the mapping loops.

**Step 5: Run the focused mapper tests**

Run the command from Step 2.

Expected: PASS.

**Step 6: Commit the v2 mapping slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion/v2 \
  core/schema/src/test/java/pro/deta/orion/schema/orion/v2
git commit -m "Map the Orion XML v2 wire model"
```

### Task 4: Add secure version dispatch and strict generated-schema validation

**Files:**

- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/OrionXmlTest.java`
- Create: `core/schema/src/test/resources/pro/deta/orion/schema/orion/orion-v2.xml`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXmlSchemaVersion.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXmlTranslator.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXmlV1Translator.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXmlV2Translator.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXmlSchema.java`
- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/OrionXml.java`

**Step 1: Write failing public XML contract tests**

Cover:

- unversioned and explicit v1 `<AccessControl>` reads into `system.accessControl`;
- full v2 fixture read/write/read equality;
- `currentSchemaVersion()` equal to v2;
- identical bytes from semantically equivalent differently ordered documents;
- generated schema containing lowercase `orion`, required version `2`, system, organization, team, and repository;
- schema and reader rejection of unknown fields/attributes;
- reader rejection of unknown roots, missing v2 versions, and unsupported versions;
- reader semantic rejection of duplicate hierarchy and ACL ids;
- DTD/external entity rejection.

**Step 2: Verify that the XML contract tests fail**

Run:

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.OrionXmlTest'
```

Expected: compilation fails because the Orion XML API does not exist.

**Step 3: Implement secure root/version detection**

`OrionXmlSchemaVersion.detect(byte[])` uses a securely configured `DocumentBuilderFactory`. It recognizes only:

```text
AccessControl + absent/1 -> V1
orion + 2              -> V2
```

Every other root/version combination fails with an `IOException` that names the unsupported root or version but
does not echo document contents.

**Step 4: Implement explicit translators**

The read-only v1 translator delegates to `AccessControlXml.read` and wraps the result in an empty-hierarchy
`OrionDocument`. The v2 translator validates bytes with `OrionXmlSchema`, unmarshals `OrionV2`, and maps it through
`OrionV2Mapper`. Only v2 implements write, using formatted UTF-8 JAXB output.

`OrionXml` reads bytes once, dispatches through the detected version, and always writes with the v2 translator.

**Step 5: Generate and compile the strict schema**

`OrionXmlSchema` calls the v2 JAXB context's `generateSchema`, compiles it with secure processing and external DTD/
schema access disabled, and returns a `ValidationResult`. Set the schema on the v2 unmarshaller as defense in depth.

**Step 6: Run the focused XML tests**

Run the command from Step 2.

Expected: PASS.

**Step 7: Commit the XML API slice**

```bash
git add core/schema/src/main/java/pro/deta/orion/schema/orion \
  core/schema/src/test/java/pro/deta/orion/schema/orion/OrionXmlTest.java \
  core/schema/src/test/resources/pro/deta/orion/schema/orion/orion-v2.xml
git commit -m "Introduce strict Orion XML v2 serialization"
```

### Task 5: Make ACL compatibility paths write v2 and publish the v2 schema

**Files:**

- Modify: `core/schema/src/main/java/pro/deta/orion/schema/acl/AccessControlXml.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/acl/AccessControlXmlTranslator.java`
- Modify: `core/schema/src/main/java/pro/deta/orion/schema/acl/AccessControlXmlV1Translator.java`
- Modify: `core/schema/src/test/java/pro/deta/orion/schema/acl/AccessControlXmlSchemaTest.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/XmlService.java`
- Modify: `core/acl/src/test/java/pro/deta/orion/acl/XmlServiceTest.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionAccessControlSchemaRoute.java`
- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/OrionStartupIT.java`

**Step 1: Change compatibility tests first**

Update `XmlServiceTest` to expect `<orion schemaVersion="2">`, to read both v1 and v2, and to preserve equivalent
ACL semantics. Retain legacy plural-item read coverage. Update schema assertions so the HTTP-facing schema validates
the v2 output and rejects v1 as a writable document.

In `OrionStartupIT`, keep validation through the existing route constant but rename helper assertions and messages
from ACL schema to Orion document schema. Do not run integration tests automatically.

**Step 2: Verify that the changed focused tests fail**

Run:

```bash
make run-test MODULE=core/acl \
  TEST='pro.deta.orion.acl.XmlServiceTest'
```

Expected: FAIL because `XmlService` still writes the v1 root.

**Step 3: Migrate compatibility serialization**

Change `XmlService.serialize(AccessControl, OutputStream)` to wrap the ACL in `OrionDocument` and call
`OrionXml.write`. Change deserialize to call `OrionXml.read` and return the system ACL projection.

Make `AccessControlXml` an explicit legacy read boundary: remove public write/current-version behavior and remove
write from its translator interface and v1 implementation. Keep legacy normalization and its focused read tests.

Change `OrionAccessControlSchemaRoute` to use `OrionXmlSchema` while retaining its current URL for HTTP compatibility.

**Step 4: Run focused schema, ACL, and HTTP compilation tests**

Run:

```bash
make run-test MODULE=core/schema \
  TEST='pro.deta.orion.schema.orion.OrionXmlTest,pro.deta.orion.schema.acl.AccessControlXmlSchemaTest'
make run-test MODULE=core/acl \
  TEST='pro.deta.orion.acl.XmlServiceTest'
mvn test -Pdev -T 4 -q -pl net/http-core -am -DskipTests
```

Expected: PASS.

**Step 5: Commit the compatibility migration**

```bash
git add core/schema core/acl/src/main/java/pro/deta/orion/acl/XmlService.java \
  core/acl/src/test/java/pro/deta/orion/acl/XmlServiceTest.java \
  net/http-core/src/main/java/pro/deta/orion/transport/http/OrionAccessControlSchemaRoute.java \
  tests/integration-test/src/integration-test/java/pro/deta/orion/test/OrionStartupIT.java
git commit -m "Make Orion XML v2 the writable configuration shape"
```

### Task 6: Verify and finish the task branch

**Files:**

- Modify: `docs/plans/current-work/hierarchical-orion-configuration/TASK.md`
- Delete: `docs/plans/current-work/hierarchical-orion-configuration/hierarchy-and-identifiers/TASK.md`
- Delete: `docs/plans/current-work/hierarchical-orion-configuration/xml-schema-v2/TASK.md`
- Modify: task files that link to either completed leaf

**Step 1: Run development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.

**Step 2: Review the complete diff**

Check line length, deterministic ordering, secure XML parser settings, exception messages, generated XSD behavior,
and `git diff --check`. Confirm no unrelated files are staged.

**Step 3: Finish task tracking**

Delete the two completed leaf task directories, remove their parent links, and replace external dependency links
with concise completed-dependency text so no Markdown link points at a deleted task node.

**Step 4: Squash and transfer**

Squash every commit unique to the task branch into one commit with subject:

```text
Introduce hierarchical Orion XML v2 [task: hierarchical-orion-configuration/xml-schema-v2]
```

Cherry-pick that commit onto `main`, run `make test` on `main`, and only then remove the worktree and branch. Do not
merge. Confirm `git worktree list` no longer contains this worktree and the branch no longer exists.
