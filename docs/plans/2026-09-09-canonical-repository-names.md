# Canonical Repository Names Implementation Plan

**Goal:** Make every Orion repository ingress resolve percent-encoded and transport-decorated spellings to one
strict canonical repository identity before authorization, provider selection, or persistence.

**Architecture:** Add one `RepositoryName` value/parser to `core/schema`, reuse the existing canonical identifier
rule for each path segment, and keep current internal `String` APIs by passing `RepositoryName.value()` at their
boundaries. Git transport decoration is handled by a second factory on the same type; all connector-, provider-,
authorization-, and route-local repository-name normalizers are removed.

**Tech Stack:** Java 21, Maven reactor, JUnit 5, AssertJ, Orion native Git storage and transports.

---

## Execution constraints

- Do not add migration, aliases, fallback parsing, dual-read behavior, or a second repository-name policy.

### Task 1: Define the shared canonical value

**Files:**

- Create: `core/schema/src/main/java/pro/deta/orion/schema/orion/RepositoryName.java`
- Create: `core/schema/src/test/java/pro/deta/orion/schema/orion/RepositoryNameTest.java`
- Reuse: `core/schema/src/main/java/pro/deta/orion/schema/orion/IdentifierRules.java`

**Step 1: Write the failing contract tests**

Cover ordinary canonical names and equivalent encoded inputs:

```java
assertThat(RepositoryName.parse("orion").value()).isEqualTo("orion");
assertThat(RepositoryName.parse("api_v2.1").value()).isEqualTo("api_v2.1");
assertThat(RepositoryName.parse("org/team/repo").value()).isEqualTo("org/team/repo");
assertThat(RepositoryName.parse("team%2Frepo").value()).isEqualTo("team/repo");
assertThat(RepositoryName.parse("team%5Crepo").value()).isEqualTo("team/repo");
assertThat(RepositoryName.parse("team\\repo").value()).isEqualTo("team/repo");
assertThat(RepositoryName.fromGitPath("/org/team/repo%2Egit").value())
        .isEqualTo("org/team/repo");
```

Use an ordinary loop with `assertThatThrownBy` to reject null, empty input, whitespace, uppercase, raw and encoded
Unicode, `+`, malformed `%` escapes, invalid UTF-8, `%252F`, leading/trailing or repeated `/`, `.`, `..`, leading,
trailing, or adjacent punctuation, and an ordinary terminal `.git`. Add Git-path cases rejecting `//repo`,
`repo.git.git`, and encoded traversal.

**Step 2: Run the contract test and verify the missing type fails compilation**

Run:

```bash
make run-test MODULE=core/schema TEST='RepositoryNameTest'
```

Expected: FAIL because `RepositoryName` does not exist.

**Step 3: Implement the smallest complete parser**

Implement an immutable final value with `parse(String)`, `fromGitPath(String)`, `value()`, value equality, and
`toString()`. Keep one private pipeline with this order:

```text
require input -> percent-decode UTF-8 once -> replace backslash with slash
-> remove one allowed Git decoration pair -> validate canonical segments
```

The percent decoder must treat only `%HH` as encoded bytes; raw `+` must stay `+`. Decode each consecutive escaped
byte sequence with a UTF-8 `CharsetDecoder` configured with `CodingErrorAction.REPORT`. Convert decoding and syntax
failures to `IllegalArgumentException` without retrying or decoding the result again.

For `fromGitPath`, remove at most one leading `/` and one terminal `.git` after decoding. Then use the same ordinary
validator. The ordinary validator rejects any remaining leading/trailing separator, empty segment, terminal `.git`,
or segment rejected by `IdentifierRules.requireCanonical`. Do not impose a segment count or length limit.

**Step 4: Run the focused contract tests**

Run:

```bash
make run-test MODULE=core/schema TEST='RepositoryNameTest,OrionHierarchyIdentityTest'
```

Expected: PASS; existing organization/team/repository ID behavior remains green.

### Task 2: Enforce canonical identity at native storage boundaries

**Files:**

- Modify: `git/git-native-storage/pom.xml`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/FileNativeGitRepositoryProvider.java`
- Modify: `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProvider.java`
- Modify: `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/FileNativeGitRepositoryProviderTest.java`
- Create: `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/InMemoryNativeGitRepositoryProviderTest.java`

**Step 1: Add failing provider tests**

Prove that both real providers canonicalize before lookup and collision checks:

```java
NativeGitRepository first = provider.create("team%2Frepo").valueOrFailure("repository");
assertThat(first.name()).isEqualTo("team/repo");
assertThat(provider.find("team/repo").valueOrFailure("repository")).isSameAs(first);
assertThat(provider.create("team/repo")).isInstanceOf(Result.Failure.class);
```

For the file provider, reopen the repository and assert canonical metadata survives restart. Corrupt its metadata
name to an uppercase or otherwise invalid spelling and assert listing/opening fails before a repository handle is
returned. Change existing file-provider fixtures that use storage identities ending in `.git` to canonical names;
keep `.git` only in transport-facing tests.

**Step 2: Run the provider tests and verify they fail on the old validators**

Run:

```bash
make run-test MODULE=git/git-native-storage \
  TEST='FileNativeGitRepositoryProviderTest,InMemoryNativeGitRepositoryProviderTest'
```

Expected: FAIL because encoded and canonical spellings are currently distinct and persisted names are not strictly
validated.

**Step 3: Replace both provider-local validators**

Add an explicit `schema` dependency to `git-native-storage`. Replace each `requireName` implementation with
`RepositoryName.parse(repositoryName).value()`. In the file provider, apply the same parse to the metadata `name`
property before returning `RepositoryMetadata`; write only the already canonical name received by `create`.

Do not change the SHA-256 directory mapping, repository layout, provider interface, or `NativeGitRepository`
constructors.

**Step 4: Run storage verification**

Run:

```bash
make run-test MODULE=git/git-native-storage \
  TEST='RepositoryNameTest,FileNativeGitRepositoryProviderTest,InMemoryNativeGitRepositoryProviderTest,NativeGitRepositoryTest'
```

Expected: PASS.

### Task 3: Make Git parsing and authorization share the canonical name

**Files:**

- Modify: `git/git-parser/pom.xml`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireBootstrap.java`
- Modify: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/GitWireBootstrapTest.java`
- Modify: `core/authorization/src/main/java/pro/deta/orion/auth/check/resource/RepositoryResource.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHook.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/auth/AuthenticatedRepositoryAccessHookTest.java`
- Modify: `net/git-transport/src/test/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryServiceTest.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java`

**Step 1: Extend failing wire and authorization tests**

Drive behavior only through `GitWireBootstrap.smartHttp`, `sshCommandData`, and native-daemon parsing. Assert that
`/team%2Frepo.git`, an SSH spelling using encoded backslash, and the equivalent plain path all put `team/repo` in
`InitialRequestData`. Add invalid wire cases for malformed/double encoding, repeated leading separators, traversal,
uppercase, and Unicode.

Update authorization coverage so the hook receives the canonical `team/repo` produced by wire parsing and grants
that exact resource. Remove the `strictRepositoryName` construction mode. Service tests must construct
`InitialRequestData` with canonical repository names because the service is downstream of wire parsing; change
provider fixtures and expected hook calls from `/demo.git` to `demo` without changing fetch/receive behavior.

**Step 2: Run the focused tests and verify the new cases fail**

Run:

```bash
make run-test MODULE=net/git-transport \
  TEST='GitWireBootstrapTest,AuthenticatedRepositoryAccessHookTest,DefaultGitNativeRepositoryServiceTest'
```

Expected: FAIL because wire and authorization still have separate normalization policies.

**Step 3: Replace the old production paths**

Add an explicit `schema` dependency to `git-parser`. In both Git wire request construction paths, call
`RepositoryName.fromGitPath(repositoryPath).value()`, then delete the public
`GitWireBootstrap.normalizeRepositoryPath` method.

Make `RepositoryResource` parse its name with `RepositoryName.parse`, establishing one authorization boundary.
Simplify `AuthenticatedRepositoryAccessHook` to one constructor and pass the supplied canonical name directly to
`RepositoryResource.of`; delete the boolean mode and local normalizer. Update `OrionGitRoute` to use that sole
constructor.

Do not keep an alias for `normalizeRepositoryPath` or a permissive authorization constructor.

**Step 4: Run the focused wire/service tests**

Run:

```bash
make run-test MODULE=git/git-parser TEST='GitWireBootstrapTest'
make run-test MODULE=net/git-transport \
  TEST='AuthenticatedRepositoryAccessHookTest,DefaultGitNativeRepositoryServiceTest'
```

Expected: PASS.

### Task 4: Canonicalize bootstrap, proxy, and ACL repository identity

**Files:**

- Modify: `git/git-native-proxy/src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java`
- Modify: `git/git-native-proxy/src/test/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProviderTest.java`
- Modify: `connectors/acl-storage/src/test/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorageTest.java`

**Step 1: Write failing bootstrap and proxy tests**

For `local:team%2Frepo`, assert that provisional resolution, `ResolvedBootstrapSource.repositoryName()`, backend
storage, and ACL load all use `team/repo`. Add a proxy-boundary test that opens/saves through an encoded spelling and
proves the canonical proxy binding is still selected rather than bypassed. Add invalid local-name cases for
absolute, traversal, malformed encoding, uppercase, and terminal `.git` input.

Replace the ACL test that treats `team%2Frepo` and `team/repo` as distinct repositories with a test proving they are
one resolved identity. Do not retain a negative test whose only purpose is preserving the old distinction.

**Step 2: Run the tests and verify canonical proxy selection fails**

Run:

```bash
make run-test MODULE=git/git-native-proxy TEST='ProxyAwareNativeGitRepositoryProviderTest'
make run-test MODULE=connectors/acl-storage TEST='NativeGitAccessControlStorageTest'
```

Expected: FAIL because local bootstrap and proxy binding lookup still use connector-local path normalization/raw
strings.

**Step 3: Use the shared parser at every proxy boundary**

Replace `ProxyAwareNativeGitRepositoryProvider.repositoryName` with `RepositoryName.parse(value).value()`. Apply it
before provisional/local source registration, persistent catalog activation, and every public provider operation
whose raw key is used for binding lookup (`exists`, `find`, `create`, `openForRead`, `openForWrite`, `saveFiles`, and
`publish`). Pass only the canonical local variable to both `binding(...)` and the backend.

Keep repository-content path validation separate; `repositoryPath(...)` validates paths inside a repository and is
not a repository identity parser.

**Step 4: Run proxy and ACL verification**

Run:

```bash
make run-test MODULE=git/git-native-proxy TEST='ProxyAwareNativeGitRepositoryProviderTest'
make run-test MODULE=connectors/acl-storage TEST='NativeGitAccessControlStorageTest'
```

Expected: PASS, with the resolved ACL identity stored as `team/repo`.

### Task 5: Unify HTTP routes and remove the final old consumer

**Files:**

- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionAdminCreateRepositoryRoute.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitPackfileRoute.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/OrionAdminCreateRepositoryRouteTest.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitPackfileRouteTest.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java`
- Modify: `net/frontend/ui/src/lib/orion-api.js`
- Modify: `net/frontend/ui/src/lib/orion-api.test.js`
- Modify: `net/frontend/ui/src/App.vue`
- Modify: `net/frontend/ui/src/App.test.js`
- Modify: `tests/git-engine-orion-adapters/pom.xml`
- Modify: `tests/git-engine-orion-adapters/src/main/java/pro/deta/orion/git/workflow/orion/OrionGitServer.java`
- Modify as required by compilation: transport-facing Orion adapter tests under
  `tests/git-engine-orion-adapters/src/test/java/pro/deta/orion/git/workflow/orion/`

**Step 1: Add failing HTTP identity tests**

For admin creation, assert `team%2Frepo` creates/lists `team/repo`, while leading `/`, terminal `.git`, uppercase,
and traversal fail before provider creation. For packfile download, create storage as `team/repo`, request
`/r/team%2Frepo.git/objects/pack/<id>.pack`, and record the provider argument. Assert both the grant and provider see
`team/repo`; invalid names return bad request without a provider read.

**Step 2: Run HTTP tests and verify the spelling mismatch fails**

Run:

```bash
make run-test MODULE=net/http-core \
  TEST='OrionAdminCreateRepositoryRouteTest,OrionGitPackfileRouteTest,OrionGitRouteNativeTest'
```

Expected: FAIL because admin and packfile routes retain independent normalization and the packfile provider sees the
raw decorated spelling.

**Step 3: Replace HTTP-local normalization**

Use `RepositoryName.parse(request.name()).value()` in the admin route and delete `normalizeRepositoryName`. In the
packfile matcher, turn the repository substring into `RepositoryName.fromGitPath(...).value()` before constructing
`RouteMatch`; map parse failure to the existing empty match/bad-request path. Delete `repositoryResourceName`, then
pass `RouteMatch.repositoryName()` unchanged to authorization and provider lookup.

**Step 4: Update the Orion Git engine adapter**

Add an explicit `schema` dependency to `tests/git-engine-orion-adapters`. Replace both calls to the deleted
`GitWireBootstrap.normalizeRepositoryPath` with `RepositoryName.fromGitPath(...).value()`. Preserve `.git` in remote
URIs because it is transport decoration, while provider calls use the canonical value. Do not add a local parser.

**Step 5: Remove frontend canonicalization**

Delete the exported `normalizeRepositoryName` function from `orion-api.js`. Make `createRepository(name)` serialize
the supplied `name` unchanged. In `App.vue`, remove the normalizer import and pass `newRepository.value.name`
unchanged to the client; retain only the empty-string check and let the server own validation.

Remove the frontend unit tests and mock for the deleted normalizer. Change the App creation fixture from the now
invalid `platform/my repo#?` spelling to a canonical example such as `platform/my-repo`, and update its displayed
clone URLs. Add/adjust the client test to assert that a decorated spelling such as `/platform/console.git` is sent
unchanged in JSON. Do not recreate the Java character policy in JavaScript.

**Step 6: Run HTTP, frontend, and adapter tests**

Run:

```bash
make run-test MODULE=net/http-core \
  TEST='OrionAdminCreateRepositoryRouteTest,OrionGitPackfileRouteTest,OrionGitRouteNativeTest'
make run-test MODULE=net/frontend/ui TEST='FrontendUiNoJavaTest'
make run-test MODULE=tests/git-engine-orion-adapters TEST='OrionGitServerTest,OrionGitClientTest'
```

Expected: PASS.

### Task 6: Verify one production path and prepare the reviewed commit

**Files:**

- Inspect: all files changed by Tasks 1-5
- Do not modify: workflow-control documents, task-tree files, or module review reports

**Step 1: Search for obsolete validators and consumers**

Run:

```bash
rg -n 'normalizeRepositoryPath|normalizeRepositoryName|strictRepositoryName|Bootstrap repository name is invalid|repositoryName must not be blank' \
  core git connectors net tests
```

Expected: no repository-identity production validator or consumer remains outside `RepositoryName`. Any match must
be classified as an unrelated content-path/ref validator or removed in its owning slice.

**Step 2: Check module dependency direction**

Check the connector POM and sources, then run the schema dependency check outside the sandbox:

```bash
rg -n '<artifactId>git-parser</artifactId>|pro\.deta\.orion\.git\.parser' \
  connectors/acl-storage/pom.xml connectors/acl-storage/src
mvn dependency:tree -Pdev -pl core/schema -am \
  -Dincludes=pro.deta.orion.git:*,pro.deta.orion.net:*
```

Expected: neither command contains a matching dependency or import. The connector's existing transitive path
through `git-native-proxy` and `git-client` is outside this task; this check prevents a new direct coupling to the
transport parser.

**Step 3: Run routine development verification**

Run outside the sandbox:

```bash
mvn verify -Pdev -T 4
```

Expected: PASS. If an unrelated integration failure appears, record exact evidence and rerun only the owning focused
test to classify it; do not change unrelated code.

**Step 4: Perform the minimal-implementation self-review**

Confirm the complete branch diff has one shared contract, no migration/fallback/dual mode, no new configuration or
persistent state, no transport dependency below `schema`, and no authorization/provider spelling mismatch. Verify
that `RepositoryAddress` and repository-content path validators remain separate because their contracts differ.
