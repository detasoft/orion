# Add a Global Git Capability Advertisement Policy

Status: todo

Replace scattered advertisement switches with one immutable, server-wide
policy that can veto a Git capability everywhere it could be advertised.

## Scope

- Define one interface keyed by canonical capability name, with an allow-all
  default and an explicit global deny set.
- Treat the policy as a veto only: it may hide an implemented capability but
  may never enable an unsupported one.
- Apply the same policy to legacy upload-pack, legacy receive-pack, and
  protocol v2 commands and features for native TCP, SSH, and Smart HTTP.
- Match valued capabilities such as `agent=<value>` by canonical name and
  handle parent/child relationships such as `fetch` and its advertised
  features consistently.
- Derive negotiation and request validation from the effective advertised set
  so a hidden capability cannot still be selected by a client.
- Install the immutable policy once at application composition time; do not
  add runtime mutation, per-session toggles, or per-repository policy.
- Remove per-protocol booleans whose only job was deciding advertisement while
  retaining configuration that controls real implementation behavior.

## Completion Criteria

- One deny decision suppresses a capability across every applicable protocol
  and transport.
- The default policy preserves the current advertisements byte for byte.
- Tests cover a legacy capability, a valued capability, a protocol v2 command,
  and a protocol v2 child feature.

## Detailed implementation plan

### Task 6: Add the global capability advertisement veto

**Files:**

- Create: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/capability/GitCapabilityAdvertisementPolicy.java`
- Create: `git/git-parser/src/test/java/pro/deta/orion/git/parser/wire/capability/GitCapabilityAdvertisementPolicyTest.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitWireConfiguration.java`
- Modify: `git/git-parser/src/main/java/pro/deta/orion/git/parser/wire/GitBlockingWireTransport.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/DefaultGitNativeRepositoryService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitBlockingWireSession.java`
- Modify: native TCP, SSH, Smart HTTP, and Dagger composition code
- Modify: parser, transport, and HTTP advertisement/negotiation tests

1. Add policy tests for default allow, an immutable deny set, matching a valued
   capability by its canonical name, and rejecting blank or parameterized deny
   keys.
2. Add cross-protocol characterization tests for the default advertisements,
   then tests denying one legacy capability, `agent`, the v2 `fetch` command,
   and one v2 fetch feature such as `shallow`.
3. Run the focused tests and record RED for the missing policy.
4. Implement the veto-only contract:

   ```java
   @FunctionalInterface
   public interface GitCapabilityAdvertisementPolicy {
       GitCapabilityAdvertisementPolicy ALLOW_ALL = ignored -> true;

       boolean allows(String canonicalName);
   }
   ```

   Provide an immutable deny-set factory; do not add mutation or an enable
   operation.
5. Build the implemented capability candidates in their owning protocol code,
   filter all candidates through the same policy immediately before creating
   the effective advertisement, and reuse that effective set for negotiation
   validation.
6. If a parent v2 command is denied, suppress its child features and reject the
   command. Denying a child feature must not suppress unrelated features.
7. Replace `GitWireConfiguration` booleans that only control advertisement with
   the policy. Keep any setting that genuinely changes implemented behavior and
   make that distinction explicit in naming.
8. Provide one immutable policy at the Orion composition root and pass it to
   native TCP, SSH, and Smart HTTP command construction.
9. Run parser, transport, HTTP, and workflow tests; expect unchanged bytes under
   `ALLOW_ALL` and consistent suppression under the deny policy.
10. Commit the capability-policy change and tests.
