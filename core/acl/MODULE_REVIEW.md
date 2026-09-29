# Module Review: `acl`

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
