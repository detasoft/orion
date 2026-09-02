# Smart HTTP Compressed Request Bodies Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Accept JGit Smart HTTP POST requests whose pkt-line body is gzip encoded.

**Architecture:** Add a package-private streaming request-body decoder at the HTTP boundary and pass its decoded
stream to the existing native Git wire parser. Keep protocol and repository limits authoritative, map unsupported
encodings to HTTP 415, and map malformed gzip data to HTTP 400 without changing identity requests.

**Tech Stack:** Java 21, Jakarta Servlet, `java.util.zip.GZIPInputStream`, JUnit 5, AssertJ, Maven Failsafe, JGit test client.

---

### Task 1: Specify Smart HTTP content-encoding behavior

**Files:**
- Create: `net/http-core/src/main/java/pro/deta/orion/transport/http/GitHttpRequestBody.java`
- Create: `net/http-core/src/test/java/pro/deta/orion/transport/http/GitHttpRequestBodyTest.java`

**Step 1: Write failing decoder tests**

Add focused cases for identity, case-insensitive `gzip`, unsupported `br`, multiple encodings, a truncated gzip
header, and a gzip stream that fails while being read. The happy-path assertion must compare the decoded bytes with
the original pkt-line bytes.

```java
@Test
void decodesGzipBodyWithoutBufferingTheWholeRequest() throws Exception {
    byte[] request = "0009done\\n0000".getBytes(StandardCharsets.US_ASCII);

    try (InputStream decoded = GitHttpRequestBody.decode(
            new ByteArrayInputStream(gzip(request)), "GZip")) {
        assertThat(decoded.readAllBytes()).isEqualTo(request);
    }
}
```

**Step 2: Run the focused test and verify it fails**

Run:

```bash
mvn test -Pdev -T 4 -q -pl net/http-core -am \
  -Dtest=GitHttpRequestBodyTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `GitHttpRequestBody` does not exist.

**Step 3: Implement the streaming decoder**

Implement `decode(InputStream, String)` with these rules:

```java
if (contentEncoding == null || contentEncoding.isBlank()
        || "identity".equalsIgnoreCase(contentEncoding.trim())) {
    return input;
}
if (!"gzip".equalsIgnoreCase(contentEncoding.trim())) {
    throw new UnsupportedContentEncodingException();
}
return new ErrorClassifyingGzipInputStream(input);
```

The wrapper must translate gzip construction, read, skip, and close failures to a dedicated
`InvalidContentEncodingException` while retaining the original cause. Do not call `readAllBytes` in production;
the Git parser and pack ingestor must continue enforcing their existing decoded protocol and pack limits.

**Step 4: Run the focused test and verify it passes**

Run the command from Step 2.

Expected: PASS with all decoder cases green.

### Task 2: Decode POST bodies at the Orion Git route

**Files:**
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java`

**Step 1: Write failing route tests**

Extend the request stub to expose `Content-Encoding`. Add one valid gzip upload-pack request, one unsupported
encoding request, and one malformed gzip request. Verify success, HTTP 415, and HTTP 400 respectively, including
the existing no-cache headers.

```java
route.handle(
        request(
                "POST",
                "/r/team/project.git/git-upload-pack",
                "application/x-git-upload-pack-request",
                null,
                Map.of(
                        "Content-Encoding", "gzip",
                        "Git-Protocol", "version=2"),
                gzip(fetchRequest(fixture.objectId())),
                repositorySecurityContext()),
        response.proxy(),
        null);
```

**Step 2: Run the route test and verify it fails**

Run:

```bash
mvn test -Pdev -T 4 -q -pl net/http-core -am \
  -Dtest=OrionGitRouteNativeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: the valid gzip case reaches pkt-line parsing as compressed bytes, and error mappings are absent.

**Step 3: Wire the decoder into `handleNativePost`**

Decode only Smart HTTP POST bodies:

```java
try (InputStream requestBody = GitHttpRequestBody.decode(
        req.getInputStream(), req.getHeader("Content-Encoding"));
     InputStreamBufferedByteInput input = new InputStreamBufferedByteInput(requestBody)) {
    // Existing bootstrap and session flow.
}
```

Catch `UnsupportedContentEncodingException` before generic IO handling and send HTTP 415. Detect
`InvalidContentEncodingException` through the existing cause-chain style and send HTTP 400. Discovery GET/HEAD
requests remain unchanged.

**Step 4: Run all HTTP-core tests**

Run:

```bash
mvn test -Pdev -T 4 -q -pl net/http-core -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

### Task 3: Verify JGit Smart HTTP interoperability

**Files:**
- Verify: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpGitRouteIT.java`
- Update only if needed: `tests/integration-test/src/integration-test/java/pro/deta/orion/test/RuntimeHttpGitRouteIT.java`

**Step 1: Run the three existing JGit workflows**

Run:

```bash
mvn verify -Pdev -T 4 -q -pl tests/integration-test -am \
  -Dexec.skip=true \
  -Dtest=NoSuchTest \
  -Dit.test=RuntimeHttpGitRouteIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all three methods pass. Do not alter the scenarios merely to avoid JGit request compression.

**Step 2: Inspect the Failsafe report**

Confirm `tests=3`, `errors=0`, and `failures=0` in
`tests/integration-test/target/failsafe-reports/TEST-pro.deta.orion.test.RuntimeHttpGitRouteIT.xml`.

**Step 3: Run routine development verification**

Run:

```bash
mvn verify -Pdev -T 4
```

Expected: PASS except for independently diagnosed Docker availability failures or still-unmigrated remote proxy
integration tests. Record such failures precisely; do not weaken or disable them in this task.

**Step 4: Commit the gzip implementation**

```bash
git add net/http-core/src/main/java/pro/deta/orion/transport/http/GitHttpRequestBody.java \
  net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitRoute.java \
  net/http-core/src/test/java/pro/deta/orion/transport/http/GitHttpRequestBodyTest.java \
  net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitRouteNativeTest.java
git commit -m "Accept gzip Smart HTTP request bodies"
make test
```

Expected: commit succeeds and the mandatory post-commit `make test` passes.
