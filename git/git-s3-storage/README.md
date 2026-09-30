# S3 Git backend bootstrap

This module implements repository metadata creation, discovery, existence checks,
and listing through `NativeGitRepositoryProvider`. Git refs, object indexing,
pack reads/writes, push/fetch, and file operations are deliberately unsupported.
The index and storage implementations throw descriptive `IOException`s; they
never pretend to contain empty Git data. Full server startup with S3 is not yet
supported because bootstrap configuration/key material need those Git APIs.

## Runtime XML connections

Named connections live under `<system><connections>` or an organization's
`<connections>`. Names are unique within each collection. A repository storage
binding explicitly selects `scope="system"` or `scope="organization"`; the
latter resolves only inside its enclosing organization. The connection owns
endpoint, region, addressing and credential references. The binding owns the
bucket/prefix. Buckets must already exist, and reading a binding never creates
repository metadata or migrates existing local data.

For MinIO, add a connection to the owning system or organization:

```xml
<connections>
  <s3 name="minio">
    <endpoint>http://localhost:9000</endpoint>
    <region>us-east-1</region>
    <accessKeyId>orion4test</accessKeyId>
    <secretKey>minio-key</secretKey>
  </s3>
</connections>
```

`minio-key` names an existing encrypted secret in that same owner's `<secrets>`
collection, created through `ConfigurationSecrets` and the key-material cipher.
An optional `<sessionToken>` also references an encrypted secret. XML never
contains plaintext secret keys or tokens. Omitting all explicit credential
fields selects the SDK default credential chain.

Inside a repository owned by an organization:

```xml
<storage>
  <s3 location="s3://orion-repositories/development">
    <connection scope="organization" name="minio"/>
  </s3>
</storage>
```

Use `scope="system"` to bind a system connection explicitly. The same connection
can serve other buckets and prefixes. Existing repository Git authorization
still governs read/write access; a reader need not own the connection.
Connection and repository creation UI/SSH commands are deferred.

For AWS, omit `<endpoint>` for standard regional resolution, or configure:

```xml
<connections>
  <s3 name="aws">
    <endpoint>https://s3.eu-west-1.amazonaws.com</endpoint>
    <region>eu-west-1</region>
  </s3>
</connections>
```

Region is needed for request signing and defaults to `us-east-1`. An explicit
endpoint preserves path-style addressing. Without an endpoint,
`<pathStyleAccess>true</pathStyleAccess>` can select it explicitly.

SSH proxies use the same connection collection, with a typed SSH definition:

```xml
<proxies>
  <proxy alias="upstream">
    <ref>refs/heads/main</ref>
    <ssh>
      <connection scope="system" name="git-host"/>
      <path>/project.git</path>
    </ssh>
  </proxy>
</proxies>
<connections>
  <ssh name="git-host">
    <host>git.example.com</host>
    <port>22</port>
    <username>git</username>
    <credentialKind>PRIVATE_KEY</credentialKind>
    <secret>git-private-key</secret>
    <knownHosts><key>ssh-ed25519 REPLACE_WITH_SERVER_PUBLIC_KEY</key></knownHosts>
  </ssh>
</connections>
```

The SSH secret resolves in the connection's owner scope. System proxies require
system SSH connections. HTTP(S)/file proxies retain their direct upstream and
credential fields. Bootstrap adoption and trusted-host-key decisions update the
canonical SSH connection. Existing per-proxy credential/configuration edits
reject a change that would silently alter another proxy's shared SSH connection.

## Bootstrap and resource ownership

Keep bootstrap XML, key-material and proxy cache repositories file-backed.
Bootstrap still supports `storage.location`, optional `storage.endpoint` alongside
it, and `storage.auth` for standalone S3 metadata selection. For that limited
bootstrap configuration, secret/token values use `env:NAME` or `file:/path`
references. Entirely S3-backed bootstrap remains unsupported because it needs
the deferred Git data APIs. Runtime S3 connections belong in XML.

`BootstrapContext` supplies one `S3Transport` singleton through application DI.
It lazily owns one SDK client, one bounded Apache HTTP pool and one default
credentials provider. Each operation captures one immutable XML snapshot and
passes endpoint/region/addressing and credentials as SDK request overrides.
Concurrent connections never mutate shared client defaults. Configuration and
secret changes affect subsequent operations, without per-connection client
replacement. Shutdown waits for requests, closes transport resources, and then
closes key-material. Repository/provider handles borrowing transport do not own
its resources.

Library callers may also construct a standalone
`S3NativeGitRepositoryProvider(location, endpoint, auth, environment)`; each such
independent provider owns its transport and must be closed. It is separate from
the application's injected runtime transport.

An explicit XML S3 binding is authoritative: S3 errors never fall back to a
same-name local repository. Listing suppresses that local entry and includes the
bound name only if its S3 metadata exists. Unbound local repositories preserve
their existing behavior.

## Persistence and failure boundaries

Metadata is stored at
`<prefix>/<sha256(canonical-repository-name)>/orion-native-repository.properties`.
The UTF-8 properties contain `name` and `defaultHead=refs/heads/main`. Canonical
name validation is shared with the file backend. Reads validate the metadata and
its key; oversized or corrupt metadata fails. Listing is paginated and ignores
objects outside this exact layout. Prefixes are normalized only for one trailing
slash and reject empty/dot/traversal path segments.

Creation uses S3 `If-None-Match: *`; the server must support conditional writes.
A duplicate reports `FILE_ALREADY_EXISTS` without replacing any metadata. Other
service failures, including a missing bucket or denied access, remain failures;
only `NoSuchKey` means an absent repository. Independent instances reread S3
without a local authoritative cache. No refs or locks are initialized.

SDK transport limits are 5 seconds for connection/acquisition, 10 seconds for
socket I/O, 15 seconds per attempt and 30 seconds per API call (including retries).
Listing may make multiple bounded API calls. These limits do not promise a
whole-list deadline or bound external credential-provider resolution.

## Verification

```sh
make test MODULE=git/git-s3-storage TEST='S3NativeGitRepositoryProviderTest,S3GitStubsTest,S3TransportTest,ConfiguredS3StorageTest'
mvn verify -Pdev -T 4 -pl git/git-s3-storage -am \
  -Dtest=S3NativeGitRepositoryProviderTest,S3GitStubsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=S3NativeGitRepositoryProviderIT -Dfailsafe.failIfNoSpecifiedTests=false
make test
```

The focused verify invocation uses the existing `MinioS3TestServer` fixture and
requires Docker. It exercises metadata persistence/reopening, independent
providers racing to create the same repository, duplicate protection, prefix
isolation, pagination, corruption, missing buckets, denied access, and safe
closure. It also verifies encrypted XML credentials, multiple buckets/prefixes
through one configured transport, credential replacement and authoritative routing.
Wire tests cover concurrent endpoints, signing regions, tokens, pagination and
shutdown. Tests do not claim S3 Git data-plane or server-startup support.
