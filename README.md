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

## Admin API

Most `/api/admin/*` routes require a bearer token from an application admin
user. For local development, issue a token through the SSH helper that
authenticates as `root` with Orion's generated server identity key:

```sh
make issue-token
```

Use the token with admin helpers or direct HTTP calls:

```sh
ORION_TOKEN="$(make -s issue-token)" make admin-acl
ORION_TOKEN="$(make -s issue-token)"
curl http://localhost:8000/api/admin/routes \
  -H "Authorization: Bearer $ORION_TOKEN"
```

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

## Roadmap

Open development areas are tracked in [ROADMAP.md](ROADMAP.md).
