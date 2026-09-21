# Module Review: `git/git-native-proxy`

## 1. Bootstrap location retains unused data and validation code

**Problem and evidence.** [BootstrapGitLocation](src/main/java/pro/deta/orion/git/proxy/BootstrapGitLocation.java)
stores `safeDescription`, but its only accessor call is one assertion in
[BootstrapGitLocationTest](src/test/java/pro/deta/orion/git/proxy/BootstrapGitLocationTest.java).
The field's null check, constructor arguments and `safeSource` helper only support that unused value.
The same class's private `repositoryPath(String, String)` method has no caller.

**Required contract.** URI/credential validation and secret-safe diagnostics remain required. Actual
transport URIs use `transportUri`; repository-path validation remains live in
[ProxyAwareNativeGitRepositoryProvider](src/main/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProvider.java).
No production diagnostic or persisted representation uses `safeDescription`.

**Minimal repair and tests.** Remove the component, its initialization and helper, and the unused private
validator. Remove only the `safeDescription` assertion; retain the surrounding URI/credential test and
all active path validation. The record is package-private and has no reflective or serialized consumer.

**Alternatives and consequences.** Adding a diagnostic consumer just to retain the field has no current
requirement. Removing it changes internal constructor shape and no observable runtime behavior.

**Confidence and priority.** High; low importance and high repair ease.

## 2. A public provisional-name query exists only for tests

**Problem and evidence.** `ProxyAwareNativeGitRepositoryProvider.provisionalRepositoryName` is called only
by [ProxyAwareNativeGitRepositoryProviderTest](src/test/java/pro/deta/orion/git/proxy/ProxyAwareNativeGitRepositoryProviderTest.java)
and [BootstrapProxyAdoptionTest](src/test/java/pro/deta/orion/git/proxy/BootstrapProxyAdoptionTest.java).
Production obtains identity from `resolveProvisional`, `prepareProvisional`, and `ResolvedBootstrapSource`.

**Required contract.** Preserve rollback, shared-source adoption, binding revocation and retained-handle
behavior. Those surrounding tests remain meaningful; the extra public lookup is not a runtime requirement.

**Minimal repair and tests.** Use existing resolution/preparation results and observable handle behavior
in the affected assertions, then remove the lookup. Preserve all lifecycle scenarios. A smaller conservative
alternative is package visibility plus `@TestOnly` if a particular internal-state assertion remains useful.

**Alternatives and consequences.** Removal narrows public Java surface without changing bindings or
persistence. Replacing useful rollback assertions with weaker checks is not an acceptable cleanup.

**Confidence and priority.** High for usage, medium for unconditional deletion; low importance and medium
repair ease because test intent must survive. No whole production or test class is proven obsolete here.
