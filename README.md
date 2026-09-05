# Orion

Orion is a lightweight Git hosting service written in Java. It is focused on
self-hosted repositories, ACL-based access control, and simple network
transports for Git clients and administrative automation.

The project is under active development, so APIs and configuration may still
change between revisions.

## Features

- File-backed repository storage.
- SSH, HTTP, HTTPS, and optional native Git transports.
- Repository ACLs with user credentials, roles, and grants.
- HTTP admin API for tokens, users, repositories, ACL files, routes, shutdown,
  and ACME certificates.
- YAML and TOML configuration with a runtime JSON schema endpoint.

## Requirements

- JDK 21.
- Maven 3.9 or newer.
- `make`, `ssh`, and `curl` are optional, but the local admin helpers use them.

## Quick Start

Start Orion with the bundled local development configuration:

```sh
export ORION_KEY_MATERIAL_PASSWORD='choose-a-local-development-password'
make run-server
```

The equivalent Maven command is:

```sh
export ORION_KEY_MATERIAL_PASSWORD='choose-a-local-development-password'
mvn -pl core/bootstrap -am -Prun-server process-classes
```

By default the server uses `orion_root` as its base directory and
`orion_root/repos` as repository storage. This directory is outside Maven's
`target` tree, so `mvn clean` does not remove generated keys or the local ACL.
The protected `material.p12` key-material store is created in the local
`orion` repository alongside `orion.xml`; its password is read only from
`ORION_KEY_MATERIAL_PASSWORD`. SSH commands use the client's normal SSH
configuration and agent, so the administrator chooses which key to use.

On first startup Orion creates a default ACL in the `orion` repository and
prints the generated `root` password:

```text
---ROOT PASSWORD: <generated-password>
```

Keep the server running and enroll a key selected by your SSH client from
another terminal:

```sh
make enroll-admin-key
```

Enter the generated recovery password at the hidden terminal prompt and select
the proved key. The dedicated `enroll-key` SSH command atomically consumes the
password and installs the selected key. Reconnect with that key before running
`make issue-token`; token issue is public-key-only and never falls back to the
recovery password. A later `--reset-root-pass` immediately invalidates prior
root SSH credentials and root JWTs without changing other users or their JWTs.
Server signing keys remain inside the protected material store and are never
exported as SSH client identities.

Default local listeners:

- SSH Git transport: `localhost:8022`
- HTTP Git transport: `http://localhost:8000/r/<repository>`
- Native Git transport: `git://localhost:9419/<repository>`

HTTPS is disabled in the initial desired state. Its listener, ACME policy, and
material references belong to the versioned `orion.xml` document. Enabling
HTTPS requires an identity with an installed certificate chain in the
protected material store; Orion does not create a self-signed fallback.

Use `--config <location>` to point Orion at a different YAML or TOML
configuration. Configuration paths can use `env:NAME`, for example
`bootstrap.baseDir: env:ORION_ROOT`, to resolve a runtime directory from an
environment variable.

## Distribution

Build runnable bootstrap artifacts with the `dev` profile:

```sh
mvn package -Pdev -pl core/bootstrap -am
```

The build attaches two single-file jars:

- `core/bootstrap/target/bootstrap-1.0-SNAPSHOT-all.jar` - a regular shaded
  `java -jar` artifact.
- `core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar` - the same
  application with Orion's POSIX/LSB launcher prepended for direct execution
  and init.d service installation.

The executable jar also gets a convenience checksum:

- `core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar.sha256`

Build the self-contained `jlink` distribution with the optional `dist` profile:

```sh
mvn package -Pdev,dist -pl core/bootstrap -am
```

The distribution build writes an unpacked image and an archive:

- `core/bootstrap/target/orion-dist/` - an unpacked Orion installation with
  `bin/orion`, `lib/orion.jar`, and a bundled Java runtime under `runtime/`.
- `core/bootstrap/target/orion-dist.tar.gz` - the same installation packaged as
  a tar archive.

Run the regular artifact directly:

```sh
java -jar core/bootstrap/target/bootstrap-1.0-SNAPSHOT-all.jar
```

Run the executable artifact from the command line:

```sh
core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar run
core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar start
core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar status
core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar stop
core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar restart
```

The launcher keeps the LSB metadata required by init.d, then passes the command
line to the JVM. Service commands such as `start`, `stop`, `status`, and
`restart` are handled by Orion's Java entry point.

For a Linux service-style installation, copy the executable artifact to the
target host and register it under `/etc/init.d`:

```sh
sudo install -d /opt/orion
sudo install -m 755 core/bootstrap/target/bootstrap-1.0-SNAPSHOT-executable.jar \
  /opt/orion/orion.jar
sudo ln -s /opt/orion/orion.jar /etc/init.d/orion
sudo service orion start
```

### Release Verification

Published releases should be verified with a detached GPG signature:

```sh
gpg --verify orion.jar.asc orion.jar
```

The self-executable jar also exposes a convenience verifier. It still delegates
cryptographic verification to `gpg`, but handles argument parsing, key download,
fingerprint checks, and temporary keyring setup in Java:

```sh
./orion.jar verify --fingerprint "<release-key-fingerprint>"
```

By default, the verifier downloads the release public key from
`https://www.deta-it.com/.well-known/orion/release.asc` and reads a sibling
signature file named `<orion.jar>.asc`. Use these options or matching
environment variables to override the defaults:

- `--key PATH` or `ORION_RELEASE_PUBLIC_KEY`
- `--key-url URL` or `ORION_RELEASE_PUBLIC_KEY_URL`
- `--fingerprint VALUE` or `ORION_RELEASE_KEY_FINGERPRINT`
- `--signature PATH` or `ORION_RELEASE_SIGNATURE`
- `--signature-url URL` or `ORION_RELEASE_SIGNATURE_URL`
- `--artifact PATH`
- `--gpg PATH` or `ORION_GPG`

The command fails closed when no expected release key fingerprint is supplied.

## Admin API

Most `/api/admin/*` routes require a bearer token from an application admin
user. For local development, issue a token through the SSH helper using the
client's normal SSH configuration:

```sh
eval "$(make -s issue-token)"
```

Use the token with admin helpers or direct HTTP calls:

```sh
make admin-acl
curl http://localhost:8000/api/admin/routes \
  -H "Authorization: Bearer $ORION_TOKEN"
```

For scripts that need the raw JWT value instead of an `export` command, use
`make -s issue-token-raw`.

Authenticated SSH users can manage only their own SSH public keys through the
exec or interactive command interface:

```text
/auth/key ls
/auth/key add candidates=all
/auth/key add candidates=<fingerprint-prefix,...>
/auth/key add key='<OpenSSH-public-key>'
/auth/key rm <fingerprint-prefix>
/auth/key rm <fingerprint-prefix> --force
```

`ls` reports algorithms, SHA-256 fingerprints, and whether a key authenticated
the current connection; it never prints public-key material. Candidate keys are
keys proved during the same SSH connection. Select all of them or use unique,
case-sensitive fingerprint prefixes. A pasted key is audit-redacted.

Removing the key used for the current connection does not disconnect that
connection, but the key is rejected on the next connection. Removing the last
SSH key requires `--force`; doing so can lock an ordinary key-only user out
until an administrator repairs its ACL or the user authenticates by another
configured method. For root, forced last-key removal durably disables root SSH,
password, and token authentication. Normal restarts preserve that lock; only
starting Orion with `--reset-root-pass` restores the one-time root key recovery
flow.

### Read-only SSH commands

Named SSH users can inspect Orion through the same command tree in exec and
interactive sessions:

```text
whoami
/repository ls
/repository/<id-or-name> show
/organization ls
/organization/<id-or-name>/user ls
/organization/<id-or-name>/repository ls
/session ls
/session/<id-or-name> show
/proxy ls
/system resource
/system/service ls
```

Repository rows contain `id`, `name`, `defaultHead`, and `refCount`; repository
objects additionally contain the backing `repositoryName`. Session rows and
objects contain `id`, `name`, `state`, `ownerId`, and `repositoryName`. Proxy
rows contain `id`, `name`, `state`, `repositoryName`, and `remote`. System
resources report processor and heap byte counts. Service rows contain `id`,
`name`, `state`, `computedState`, and `terminal`.

Dynamic selectors resolve in this order: exact canonical ID, unique authorized
ID prefix, then exact authorized display name. Every collection and selector
is filtered using the concrete resource's ACL before rows, ambiguity candidates,
navigation entries, or completion candidates are produced. Administrators can
inspect every resource. Other users see readable repositories, their own or
repository-associated sessions, and repository-associated proxies. System
resources and lifecycle services are administrator-only.

An available source with no entries returns an empty table. A domain whose
runtime adapter is not installed returns `SERVICE_UNAVAILABLE`; an unexpected
backend failure returns a sanitized `HANDLER_FAILED`. Organization, session,
and proxy adapters are supplied by their owning runtime subsystems and are not
fabricated by scanning configuration or storage files.

The configuration JSON schema is public and does not require an admin token:

```sh
curl http://localhost:8000/schemas/orion-configuration.schema.json
```

## Configuration

Orion looks for configuration in this order:

1. `config.toml`
2. `config.yml`
3. `/etc/orion/orion.yml`
4. `classpath://config.toml`
5. `classpath://config.yml`

The bundled local configuration is equivalent to:

```yaml
bootstrap:
  baseDir: orion_root
  workDir: work
  threadPoolSize: 10
  accessControl:
    location: local:orion
    ref: refs/heads/main
    paths:
      - orion.xml
    createDefaultIfMissing: true
  keyMaterial:
    location: local:orion
    ref: refs/heads/main
    path: material.p12
    password: env:ORION_KEY_MATERIAL_PASSWORD
    clusterId: orion
    serverSigning:
      algorithm: RSA
      active:
        alias: server-signing-v1
        version: 1
      verification: []
storage:
  location: repos
  createOnPush: true
transport:
  defaultAddress: localhost
  git:
    enabled: true
    port: 9419
  http:
    enabled: true
    port: 8000
  ssh:
    enabled: true
    port: 8022
```

HTTPS and ACME are configured under `<system>` in the versioned `orion.xml`:

```xml
<https>
  <enabled>false</enabled>
  <address>0.0.0.0</address>
  <port>8443</port>
  <publicUrl>https://orion.example.test</publicUrl>
  <identity alias="https-identity" version="1"/>
  <clientAuthentication>disabled</clientAuthentication>
  <clientTrustAnchors/>
  <acme>
    <enabled>false</enabled>
    <directoryUrl>acme://letsencrypt.org/staging</directoryUrl>
    <accountEmail>admin@example.test</accountEmail>
    <domains><domain>orion.example.test</domain></domains>
    <accountMaterial alias="acme-account" version="1"/>
    <authorizationTimeoutSeconds>60</authorizationTimeoutSeconds>
    <orderTimeoutSeconds>60</orderTimeoutSeconds>
    <agreeToTermsOfService>false</agreeToTermsOfService>
    <allowRequestedDomains>false</allowRequestedDomains>
  </acme>
</https>
```

ACME account and domain private keys remain inside `material.p12`. The ACME
admin route returns only the public certificate chain.

`storage.location` supports local filesystem storage with `file:` locations.
`bootstrap.accessControl.location` can point to a local ACL directory with
`file:` or to a repository in Orion's configured storage with
`local:<repository>`. The codebase contains an S3 storage module, but the
current top-level S3 repository provider is not implemented yet.

## ACL Startup Model

ACL loading does not depend on Orion network transports. Startup first reads
configuration, initializes repository storage, resolves
`bootstrap.accessControl`, and loads the ACL directly from the configured
storage backend. Only after the ACL is available does Orion start external
transports.

That means `transport` settings never participate in ACL bootstrap. A
repository-backed ACL such as `local:orion` is opened directly through the
native storage backend, while a filesystem ACL uses the configured `file:`
directory. Orion creates an empty configured repository or ref during first
startup, commits the generated `orion.xml`, and reuses that commit on restart.
Accepted pushes to the configured ref reload the ACL; an invalid candidate
leaves the last valid ACL active. The internal repository is returned by
`GET /api/admin/repositories` together with user-created repositories.
Remote storage credentials, for example S3 credentials, should come from the
backend's normal environment or provider-specific mechanisms.

## Development

Run routine local tests with the `dev` Maven profile:

```sh
mvn test -Pdev
```

Run the standard development verification:

```sh
mvn verify -Pdev
```

Unit tests use normal log levels by default. Enable project DEBUG logging for a
local test run with:

```sh
mvn test -Pdev -Dorion.test.debug=true
```

Tune the test log level and categories when needed:

```sh
mvn test -Pdev \
  -Dorion.test.debug=true \
  -Dorion.test.log.level=TRACE \
  -Dorion.test.log.categories=pro.deta.orion.git,org.eclipse.jgit=WARN
```

Run all tests with Java Flight Recorder analytics:

```sh
make test-jfr
```

The run uses allocation-heavy JFR settings and writes a report under
`target/test-analytics/<run-id>/` with `index.html`, `summary.md`, CSV files,
raw `.jfr` recordings, and SVG flame graphs. Maven prints the expected
`file://.../index.html` report URL at the start of the JFR test run. The HTML
report embeds the CSV tables with sortable columns and hides classloader
allocation rows by default with a checkbox. For allocation work, start with
`index.html`, `byte-array-test-allocations.csv`, `test-allocations.csv`, and
`flamegraph-test-alloc.svg`; they show allocation stack hotspots and make it
easier to find paths that materialize data into `byte[]` before writing instead
of parsing or forwarding it incrementally. Raw unfiltered allocation files are
also kept in the report directory.

## Repository Layout

- `core/` - configuration, ACL, authorization, Git engine, storage, and common
  runtime utilities.
- `net/` - Git and HTTP transports.
- `integration/` - integration modules for external services.
- `tests/` - shared test support, integration tests, and test utilities.
- `infrastructure/` - Terraform and deployment-related helpers.
- `docs/plans/` - design and implementation notes for larger changes.

## License

Orion is source-available under the Business Source License 1.1 with
DETA PRO B.V. as the licensor. DETA PRO B.V. is available at
https://www.deta-it.com. Each version changes to the GNU Affero General Public
License version 3 or later four years after its first public distribution.

Free production use is allowed for internal self-hosted business operations.
Hosted access, SaaS, managed services, resale, embedding in a commercial
product or service, paid support, paid maintenance, paid operations, and paid
professional services where Orion is a material part of the offering require a
commercial agreement with DETA PRO B.V.

See [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and
[TRADEMARKS.md](TRADEMARKS.md). The canonical license texts are included in
[LICENSE-BUSL-1.1.txt](LICENSE-BUSL-1.1.txt) and
[LICENSE-AGPL-3.0-or-later.txt](LICENSE-AGPL-3.0-or-later.txt). Release
distribution dates are tracked in [RELEASES.md](RELEASES.md).

Commercial licensing and legal questions can be sent to info@deta-it.com or
info@detasoft.net.

## Contributing

Contributions are accepted under the process in
[CONTRIBUTING.md](CONTRIBUTING.md). Contributors keep copyright in their work
and grant DETA PRO B.V. the rights described in [CLA.md](CLA.md).

## Roadmap

Open development areas are tracked in [ROADMAP.md](ROADMAP.md).
