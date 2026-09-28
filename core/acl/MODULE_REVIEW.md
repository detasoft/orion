# Module Review: `acl`

## 2. First-start root authentication contradicts the enrollment documentation

**Problem.** With missing ACL and normal defaults, startup creates an unmarked reusable password credential.
Ordinary authentication accepts it; SSH enrollment adds keys without consuming it. The documented first-start
procedure promises a recovery-only password consumed by enrollment.

**Sources.** [Startup selection](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L150),
[default creation](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1054),
[authentication](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L547),
[enrollment](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L566), and
[SSH consumer](../../net/git-transport/src/main/java/pro/deta/orion/transport/git/auth/OrionSshAuthenticator.java#L143).
The [bootstrap test](../bootstrap/src/test/java/pro/deta/orion/component/InternalConfigurationRepositoryLifecycleIT.java#L78)
explicitly expects ordinary password authentication across restart.

**Documented behavior and contract.** [First-start instructions](../../README.md#L55) describe a one-time
recovery password and public-key-only token issuance. Creating a usable initial administrator is required;
the separate initial and reset credential models are a policy choice. The current code and test contradict
the instructions, so the intended policy needs a decision.

**Minimal repair.** If the documented policy is intended, reuse `resetRootPassword` with the existing empty
snapshot for first creation, remove the alternate creation helpers and algorithm seam, and update meaningful
bootstrap/enrollment/authentication coverage. Preserve loading of existing stored configurations.

**Alternatives and consequences.** Documenting a reusable initial password preserves current behavior but
must explain its continued validity after enrollment. Reusing recovery changes fresh-install authentication
and must verify ordinary authentication rejection, single enrollment consumption and reconnect behavior.
Neither option requires a new schema or service.

**Confidence.** High on the mismatch and execution path; medium on the intended product policy.

**Priority signals.** Importance: high, administrator credential lifetime. Repair ease: medium, existing
recovery mechanism with cross-boundary tests and a policy decision.

## 3. User mutations synchronously reload and then request another reload

**Problem.** `createOrUpdateUser` saves and synchronously reloads the ACL, then publishes a self-handled event
and waits for another full load, validation and publication. An event timeout can follow an already applied
mutation.

**Sources.** [Mutation](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1602),
[redundant wait](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1607),
[helper](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L939), and
[synchronous activation](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L1767).
The ACL service is the event's sole production publisher and handler. Storage has its own
[external change subscription](../../connectors/acl-storage/src/main/java/pro/deta/orion/acl/storage/NativeGitAccessControlStorage.java#L143).
[User mutation tests](src/test/java/pro/deta/orion/acl/OrionAccessControlServiceImplTest.java) cover persistence,
owning-file preservation and concurrent credential updates.

**Documented behavior and contract.** Completed mutations must be persisted and active. The first reload
already establishes this. Revision checks, serialized mutations and external storage notifications remain
required; the second self-event boundary supplies no distinct guarantee. No separate requirement was found.

**Minimal repair.** Delete the post-save self-event call and its helper, retaining synchronous reload and
the storage subscription. With no remaining production publisher, remove the internal event handler/type
and migrate its test consumers in the same replacement; preserve meaningful external-change coverage.

**Alternatives and consequences.** Event-only activation changes synchronization and error propagation.
Keeping both repeats work and adds a post-success failure boundary. Local deletion changes no wire or
persistence format; verify activation and external reload behavior through existing supported interfaces.

**Confidence.** High for repository consumers; external event publishers were not established.

**Priority signals.** Importance: medium, repeated work and post-success timeouts. Repair ease: high, local
deletion with existing behavioral coverage.

## 4. `XmlService` duplicates the canonical XML serialization boundary

**Problem.** The public facade forwards document read/write directly to schema's `OrionXml` and adds ACL
projection methods used only by tests. It has no injection, registration or independent implementation.

**Sources.** [Facade](src/main/java/pro/deta/orion/acl/XmlService.java),
[sole production consumer](src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java#L88),
[canonical owner](../schema/src/main/java/pro/deta/orion/schema/orion/OrionXml.java), and
[behavior tests](src/test/java/pro/deta/orion/acl/XmlServiceTest.java). Bootstrap, storage connector and
integration tests also call the facade.

**Documented behavior and contract.** The [README](../../README.md) identifies the shared JAXB reader/model.
Versioned XML input/output and legacy loading are required. The additional service object and forwarding
Java API are incidental; schema owns these contracts already.

**Minimal repair.** Use `OrionXml.read/write` directly, migrate every real repository caller and preserve
meaningful XML behavior coverage through the canonical boundary. Remove the facade and redundant tests only
after identifying which cases are already covered.

**Alternatives and consequences.** Narrowing visibility retains duplicate delegation. Deletion changes
internal Java references without changing XML, persistence or exception contracts. A new serializer
abstraction would add unnecessary ownership.

**Confidence.** High for repository consumers; external binary compatibility is not an established contract.

**Priority signals.** Importance: low, redundant boundary. Repair ease: medium, test migration across modules.
