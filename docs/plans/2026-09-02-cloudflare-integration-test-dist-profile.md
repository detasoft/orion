# Cloudflare Integration Test Dist Profile Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Run the state-mutating Cloudflare integration test only when Maven profile `dist` is active.

**Architecture:** A JUnit system-property condition protects the test class. The integration-test POM supplies a
safe default and switches it on only from its `dist` profile, while the existing API-token assumption remains in
place.

**Tech Stack:** Maven 3, Maven Failsafe 3.3.0, JUnit Jupiter 5.11.0, Java 21

---

### Task 1: Specify the profile guard

**Files:**

- Create: `tests/integration-test/src/integration-test/java/pro/deta/orion/cloudflare/CloudflareIntegrationProfileTest.java`
- Test: `tests/integration-test/src/integration-test/java/pro/deta/orion/cloudflare/CloudflareIntegrationProfileTest.java`

**Step 1: Write the failing configuration test**

Add assertions that `IntegrationCloudflareIT` has an `EnabledIfSystemProperty` condition named
`cloudflare.it.enabled` matching `true`. Read `tests/integration-test/pom.xml` and assert that the
property defaults to `false`, is forwarded to the test JVM, and becomes `true` inside profile `dist`.

**Step 2: Run the test to verify it fails**

Run:

```bash
mvn test -Pdev -T 4 -q -pl tests/integration-test -am \
  -Dtest=CloudflareIntegrationProfileTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `CloudflareIntegrationProfileTest` fails because the condition and Maven properties do not exist yet.

### Task 2: Implement the profile guard

**Files:**

- Modify: `tests/integration-test/src/integration-test/java/pro/deta/orion/cloudflare/IntegrationCloudflareIT.java`
- Modify: `tests/integration-test/pom.xml`

**Step 1: Add the JUnit condition**

Annotate `IntegrationCloudflareIT` with:

```java
@EnabledIfSystemProperty(named = "cloudflare.it.enabled", matches = "true")
```

**Step 2: Configure Maven**

Define `cloudflare.it.enabled` as `false`, forward it through Failsafe's
`systemPropertyVariables`, and add a `dist` profile that changes the value to `true`. Preserve the existing
Failsafe `workingDirectory` customization.

**Step 3: Run the configuration test**

Run the focused Maven command from Task 1.

Expected: PASS.

**Step 4: Verify the guarded integration test without `dist`**

Run:

```bash
mvn verify -Pdev -T 4 -q -pl tests/integration-test -am \
  -Dit.test=IntegrationCloudflareIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS with `IntegrationCloudflareIT` skipped and no Cloudflare API request.

**Step 5: Review the final diff**

Confirm that only the new guard, its Maven profile configuration, its regression test, and these plan documents
were added. Leave all pre-existing working-tree changes uncommitted.
