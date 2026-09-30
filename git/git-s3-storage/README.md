# S3 Git backend

This module stores repository metadata, refs, pack bytes and object indexes in S3.
Runtime repositories support push/fetch and file operations. Process-level S3 storage
also supports bootstrap configuration and key material without persistent local data.

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
Create these repositories from the UI's **Create repository** dialog: choose S3,
enter `organization/team/repository`, select a permitted storage connection, and
enter `s3://bucket/prefix`. **Add connection** and **Edit connection** manage S3
storage connections independently of the UI's server sign-in settings. Credentials
are write-only: omitted replacement fields preserve existing values, and the
explicit remove-token control clears a session token. Replacing credentials
creates new owner-bound encrypted secret references; it never changes a secret
that another consumer may share. Nonadministrators must supply explicit credentials.
Only system administrators can select system connections or the server AWS
credential chain.

SSH uses existing connections and accepts no secret arguments:

```sh
ssh -p 8022 user@orion.example 'repository create acme/team/local'
ssh -p 8022 user@orion.example 'repository create acme/team/archive connection=organization/minio location=s3://orion-repositories/archive'
ssh -p 8022 root@orion.example 'repository create acme/team/archive connection=system/minio location=s3://orion-repositories/archive'
```

The existing organization and team must be configured before S3 creation.
Repository CREATE and CONNECTION_USE on the selected connection are both required.
The binding is committed
to configuration before S3 metadata creation. A storage failure retains the binding
and returns retry guidance: repeat the identical request to finish. A different
binding or an existing local repository is rejected. Metadata is never overwritten,
and an S3 failure never falls back to local storage.

Connection grants require the `CONNECTION` selector. CREATE permits creation,
READ_WRITE permits modification, READ permits inspection, and CONNECTION_USE permits binding
a new repository. Creation, modification, and use are independent permissions.
READ permits inspection alone; CONNECTION_USE and READ_WRITE also expose the safe settings
needed for their operations. A connection grant never grants repository, branch,
network, or administration access. Organization role assignment
and deny-wins inheritance apply normally. For example, an organization user's
direct grants may contain:

```xml
<grants>
  <grant id="create-storage"><info><expression><key>CONNECTION</key><value>archive-*</value></expression>
    <expression><key>CREATE</key><value>true</value></expression></info></grant>
  <grant id="change-storage"><info><expression><key>CONNECTION</key><value>archive-*</value></expression>
    <expression><key>READ_WRITE</key><value>true</value></expression></info></grant>
  <grant id="use-storage"><info><expression><key>CONNECTION</key><value>archive-*</value></expression>
    <expression><key>CONNECTION_USE</key><value>true</value></expression></info></grant>
  <grant id="create-repository"><info><expression><key>REPOSITORY</key><value>acme/team/*</value></expression>
    <expression><key>CREATE</key><value>true</value></expression></info></grant>
</grants>
```

These selectors resolve only within the user's organization. Organization grants
cannot authorize system connections or server default credentials. An administrator
can bind a system connection to an organization repository; later repository reads
continue to require only repository permissions.

HTTP clients use `GET /api/storage/connections?organization=acme` for safe connection
projections and their configuration revision. POST to the same URL accepts
`{revision, create, connection: {name, endpoint, region, pathStyleAccess,
accessKeyId, secretKey, sessionToken, defaultCredentials}}`. Omit `organization` for
system scope. Null secret fields preserve stored values on update; an empty
session token clears that token. An empty secret key is invalid. Selecting
`defaultCredentials: true` requires a system administrator and excludes explicit
credential fields. POST `/api/admin/repositories` accepts
`{name, connectionScope: "organization", connection: "minio", location: "s3://bucket/prefix"}`;
omit storage fields for local creation. Connection mutations require the returned
revision and return 409 on stale revisions. Repository storage failures return 503
with `retryable: true` and a safe error message.

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

Process configuration can select S3 with `storage.location`, optional
`storage.endpoint`, and `storage.auth`. Bootstrap sources such as `local:orion`
then use that backend for configuration and key material. Secret/token values in
`storage.auth` use `env:NAME` or `file:/path` references; the key-material password
also remains an external bootstrap input. After initial creation, a fresh local
base directory can reopen the same S3 state with key-material creation and default
configuration creation disabled. S3 persists the configuration, encrypted secrets,
server signing identity and SSH host keys; local temporary files are disposable.

Runtime S3 connections belong in XML. XML repository bindings cannot select S3
for bootstrap or proxy cache repositories: those repositories use the process
backend selected before XML is loaded. This restriction does not require that
process backend to be file-backed.

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
repository metadata on every lookup. Empty repositories use an unborn `refs/heads/main` HEAD.

Under the same repository prefix, `packs/<PackId>.data` holds internal compressed
pack bytes and `indexes/<PackId>.index` holds one immutable, versioned manifest.
`refs` contains HEAD and all refs; conditional ETag writes apply a multi-ref update
atomically across servers while merging unrelated ref changes. Conflicting changed
refs or HEAD fail without publishing any part of that update.

`GitStorageApi` opens operation-scoped `GitStorageAccess` instances. `PackHandle`
is the single-pack random-access handle. S3 stages writable packs in temporary files;
`flush` uploads durable bytes, and closing a dirty handle uploads before removing
the temporary file. Reads before flush use staging; remote reads use bounded ranges.
Large packs use multipart upload. Repository handles borrow the shared transport.

Byte durability, index publication and ref application are independent. Failed index
publication leaves durable but undiscoverable pack bytes. Failed ref application
leaves published packs discoverable. No failure automatically deletes orphan bytes.
Incomplete index entries are shared across accesses of one index owner but are lost
when that owner is reopened; interrupted pushes can be retried. Published bytes and
manifests require no local state after restart.

One server retains one index owner per repository. Its first published lookup loads
all immutable manifests with a paginated LIST and GETs; later lookups use the shared
in-memory manifests and object locations. Successful index publication updates this
cache before returning, including for accesses opened earlier. Failed initial loads
leave no partial cache and may be retried. Recreating the owner reloads durable S3
state; publications from independent servers or providers are not refreshed live.
Memory is proportional to the repository's published objects. No repository-wide
index or remote object record is written per added object.

Configured repositories retain their owner across credentials, region and addressing
changes at the same endpoint and normalized location. Each provider operation still
resolves current XML credentials, and subsequent S3 requests from existing accesses
use the updated request settings. Endpoint/location changes select a separate owner;
returning to a previously used location reuses its owner, including publications by
accesses that were still active there. Removing a binding stops routing to its S3
owner. Opened physical repositories stay owned until the configured provider closes,
so their existing accesses may finish. Closing a provider prevents new accesses and
releases its cache after active accesses finish; a standalone provider also closes
its transport.

SDK transport limits are 5 seconds for connection/acquisition, 10 seconds for
socket I/O, 15 seconds per attempt and 30 seconds per API call (including retries).
Listing may make multiple bounded API calls. These limits do not promise a
whole-list deadline or bound external credential-provider resolution.

## Verification

```sh
make test MODULE=git/git-s3-storage TEST='S3*Test,ConfiguredS3StorageTest'
make test-all MODULE=git/git-s3-storage TEST='S3*IT'
make test-all MODULE=net/http-core TEST=S3GitTransportIT
make test-all MODULE=tests/integration-test TEST=S3BootstrapRestartIT
make test
```

The focused verify invocation uses the existing `MinioS3TestServer` fixture and
requires Docker. It exercises metadata persistence/reopening, independent
providers racing to create the same repository, duplicate protection, prefix
isolation, pagination, corruption, missing buckets, denied access, and safe
closure. It also verifies encrypted XML credentials, multiple buckets/prefixes
through one configured transport, credential replacement and authoritative routing.
Wire tests cover concurrent endpoints, signing regions, tokens, pagination and
shutdown. Data-plane tests cover pack persistence, reopening, ref conflicts, multipart
uploads, failed publication and bounded reads. The HTTP test pushes, clones, sends a
thin delta and clones through a fresh S3 provider.
The cold-start test seeds configuration and key material directly in MinIO, then
starts the real runtime once from an empty local directory with creation disabled.
It verifies the loaded configuration, signing identity and encrypted secret.
The restart test starts the real runtime twice against MinIO, using a
new empty local directory after deleting the first, with creation disabled. It checks
unchanged configuration and key-material bytes, Git refs, signing identity, SSH
host keys and decryption of a previously saved configuration secret.
