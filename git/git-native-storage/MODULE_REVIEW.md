# Module Review: `git/git-native-storage`

## 1. The old packfile URI builder is unreachable

**Problem and evidence.** [NativePackfileUriBuilder](src/main/java/pro/deta/orion/git/nativestorage/upload/NativePackfileUriBuilder.java)
has no production, test, resource, registration, or reflective consumer. It is an ordinary static utility
with a private constructor. Active URI advertisement uses
[NativeGitRepositoryContext.packUri](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/NativeGitRepositoryContext.java)
through `FetchPack`.

**Required contract.** Packfile URI advertisement remains required. No current contract requires a second,
unreachable builder or its independent encoding policy.

**Minimal repair and tests.** Delete the utility and its empty package. No dedicated tests need removal;
preserve active parser/transport pack-URI tests. Do not change current URL encoding as part of this deletion.

**Alternatives and consequences.** Wiring the old builder back in would alter live URL construction and
is not necessary for cleanup. Deletion removes unused Java API only; external binary consumers were not
established.

**Confidence and priority.** High; low importance and high repair ease.

## 2. Repository identity retains an unused description alias

**Problem and evidence.** [NativeGitRepository.description](src/main/java/pro/deta/orion/git/nativestorage/NativeGitRepository.java)
and its override in
[PolicyBoundNativeGitRepository](../git-native-proxy/src/main/java/pro/deta/orion/git/proxy/PolicyBoundNativeGitRepository.java)
have no production or test callers. `name()` is the live repository identity API.

**Required contract.** Repository identity and policy enforcement remain required. No separate description
behavior or documented compatibility obligation was found.

**Minimal repair and tests.** Remove the two methods together. No test needs deletion; preserve `name()`
and all policy-bound repository tests.

**Alternatives and consequences.** A second alias adds API surface without a current user. Deletion
changes unused internal Java methods, without changing names, publication, or repository visibility.

**Confidence and priority.** High for current references; low importance and high repair ease.

## 3. Tests retain a redundant conditional-save entrypoint

**Problem and evidence.** `NativeGitRepository.saveFilesIfVersion` and
[NativeRepositoryFileSaver.saveFilesIfVersion](src/main/java/pro/deta/orion/git/nativestorage/NativeRepositoryFileSaver.java)
are called only by four cases in
[NativeGitRepositoryTest](src/test/java/pro/deta/orion/git/nativestorage/NativeGitRepositoryTest.java) and
[NativeGitFileUpdateTest](src/test/java/pro/deta/orion/git/nativestorage/NativeGitFileUpdateTest.java).
Actual conditional writers use `prepareFileUpdate` followed by `publishPack`, including
[NativeGitAccessControlStorage](../../connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java)
and [NativeGitKeyMaterialContentStore](../../core/bootstrap/src/main/java/pro/deta/orion/NativeGitKeyMaterialContentStore.java).

**Required contract.** Preserve stale-version rejection, independent-provider CAS behavior, untouched
files, and retention of an already validated pack when refs are rejected. These tests are not obsolete.

**Minimal repair and tests.** Exercise the existing prepare/publication API in those four tests, preserving
exception mapping where relevant, then remove both convenience methods. No new service or replacement
request type is needed.

**Alternatives and consequences.** Keeping a small documented convenience method is possible; its current
only justification is tests. Removal narrows internal Java API but must not weaken the tested CAS contract.

**Confidence and priority.** High for test-only usage, medium for deletion priority; low importance and
medium repair ease because useful assertions must migrate before removing the wrapper.
