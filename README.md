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
make run-server
```

The equivalent Maven command is:

```sh
mvn -pl core/bootstrap -am -Prun-server process-classes
```

By default the server uses `target/orion_root` as its base directory and
`target/orion_root/repos` as repository storage. On first startup it creates a
default ACL in the `orion` repository and prints the generated `root` password
once to stdout:

```text
---ROOT PASSWORD: <generated-password>
```

Default local listeners:

- SSH Git transport: `localhost:8022`
- HTTP Git transport: `http://localhost:8000/r/<repository>`
- HTTPS Git transport: `https://localhost:8443/r/<repository>`
- Native Git transport: disabled unless `transport.git.enabled` is set to
  `true`

The HTTPS listener uses a self-signed certificate unless
`transport.https.ksystore` is configured.

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
user. For local development, issue a token through the SSH helper that
authenticates as `root` with Orion's generated server identity key:

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
  baseDir: target/orion_root
  workDir: work
  threadPoolSize: 10
  accessControl:
    location: local:orion
    branch: master
    paths:
      - orion.xml
    createDefaultIfMissing: true
storage:
  location: file:target/orion_root/repos
  createOnPush: true
transport:
  defaultAddress: localhost
  git:
    enabled: false
    port: 9418
  http:
    enabled: true
    port: 8000
  https:
    enabled: true
    port: 8443
    acme:
      enabled: false
      directoryUrl: acme://letsencrypt.org/staging
      accountKeyPath: acme/account.keypair
      domainKeyPath: acme/domain.keypair
      certificatePath: acme/nginx.pem
  ssh:
    enabled: true
    port: 8022
```

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
storage backend, while a filesystem ACL uses the configured `file:` directory.
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
