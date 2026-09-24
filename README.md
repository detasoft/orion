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

Initialize and start Orion with the bundled local development configuration:

```sh
export ORION_KEY_MATERIAL_PASSWORD='choose-a-local-development-password'
make init-server
```

The equivalent Maven command is:

```sh
export ORION_KEY_MATERIAL_PASSWORD='choose-a-local-development-password'
mvn -pl core/bootstrap -am -Prun-server -Dorion.run.arguments="--create-if-missing" process-classes
```

For subsequent starts, use `make run-server` with the same material password.
Both goals accept additional application options through `ORION_ARGS`.
`init-server` explicitly permits creating missing key material; do not use it
to recover lost keys from an existing installation. Restore its matching
[key-material backup](docs/key-material-backup-and-restore.md) instead.

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

`POST /api/admin/token` validates the Basic header syntax, then accepts at most
4096 bytes of request body. Larger bodies receive HTTP 413 before JSON parsing
or credential verification, including when Content-Length is absent. An empty
body uses the default token TTL.

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

Every `ls` command above supports a bounded query over its declared columns:

```text
/session ls where state=RUNNING repositoryName!=null
/repository ls columns=id,refCount page=2 page-size=50
/system/service ls columns=id,terminal where terminal=true format=table
```

Predicates after `where` are combined with AND and use exact, type-aware `=`
or `!=` comparisons. Numeric columns require numbers and boolean columns accept
only `true` or `false`. The reserved value `null` selects an absent value;
quote or escape other values using the ordinary command-line syntax. Unknown
fields, duplicate or empty selected columns, invalid values, and non-positive
pages are rejected with `INVALID_ARGUMENTS` and SSH exit status 2.

Pages are one-based, default to 100 rows, and allow at most 500 rows. Filtering
is applied before `columns`, then pagination preserves the command's canonical
ID order. The query always runs after domain ACL filtering: hidden resources do
not contribute rows, `matched`, `next`, completion values, or error details.

`format=plain` emits escaped TSV with a header; `format=terse` emits the same
records without a header; `format=json` preserves strings, numbers, booleans,
and null; and `format=table` requests the width-aware interactive table. Table
format requires a PTY. With no format, exec uses plain output and an interactive
session uses a table when it fits, falling back to plain TSV otherwise.

For automation, for example:

```sh
ssh operator@orion '/repository ls columns=id,refCount format=json where refCount!=0'
ssh operator@orion '/session ls columns=id,state format=terse page-size=50'
```

JSON is one object followed by a newline, with columns and row keys in declared
selection order:

```json
{"columns":["id","refCount"],"rows":[{"id":"demo","refCount":3}],"page":{"number":1,"size":100,"matched":1,"next":null}}
```

JSON always includes page metadata. Plain, terse, and table output append a
line such as `# page=1 page-size=2 matched=3 next-page=2` only when a page
parameter was explicit, the requested page was not the first, or more matching
rows remain. `next-page=null` means there is no continuation.

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

SSH host keys are typed `SSH_HOST` entries in the material store. By default,
the SSH transport serves every cluster-scoped entry of that type and creates
`ssh-host-rsa-v1` and `ssh-host-ec-v1` when none exist. A node can select its
own logical key families explicitly. A logical alias selects the highest stored
version; a concrete alias with a `-v<version>` suffix selects that exact entry:

```yaml
transport:
  ssh:
    hostKeys:
      - alias: node-a-rsa
      - alias: node-a-ec
      - alias: node-a-ed25519-v2
```

### Orion XML schema

[orion-v2.xsd](docs/schemas/orion-v2.xsd) provides XML completion and structural
validation for the current `orion.xml` format. It is generated from the same
JAXB model used by Orion's reader. Associate it with your document in the XML
editor, or add a schema hint (adjust the relative path for your file):

```xml
<orion schemaVersion="2"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:noNamespaceSchemaLocation="../docs/schemas/orion-v2.xsd">
  <system>
    <accessControl><users/><roles/><grants/></accessControl>
  </system>
  <organizations/>
</orion>
```

The path above is relative to a document in `orion/`. For use outside this
checkout, place the XSD beside your XML and use `orion-v2.xsd` as the location.
The schema describes v2 (`<orion schemaVersion="2">`); legacy v1 documents
with an `<AccessControl>` root require conversion to v2 before using it.

Run `make xml-schema` after changing the JAXB wire model to regenerate the
checked-in XSD. A running Orion also exposes its generated schema at
`GET /schemas/orion-admin-acl.xsd` and
accepts XML for structural validation at `POST /schemas/orion-admin-acl.xsd`.
The POST response contains `valid` and, on failure, `message`.

XSD checks XML structure, required fields and wire types. Semantic rules such
as scoped role references, role cycles and HTTPS configuration constraints
are checked separately by Orion when reading the document. Orion uses its own
schema for validation and does not fetch the document's schema hint.

Organization identities are confined to repositories addressed as
`organization/team/repository`. Repository and branch grants still apply inside
that boundary; wildcard grants never cross it. HTTP repository listings, SSH
catalogs and pending decisions use the same organization boundary. System
administration, system SSH credentials and system token issuance require a
system identity, even when an organization user has the same user ID. OIDC access
tokens carry the organization identity and use these same checks.

### Configure an OIDC provider

Providers belong to an organization, including the ordinary `default`
organization created on first initialization. Organizations do not inherit
providers from `default`. Configure each organization that needs browser login.
Provider configuration is stored in the versioned `orion.xml`, not the startup
YAML/TOML file.

1. **Set Orion's public address.** In `system/https/publicUrl`, set the HTTPS
   origin users open in their browsers, for example `https://orion.example.test`.
   Use the external address when Orion runs behind a reverse proxy. Do not include
   an application path, query, or fragment. This setting does not enable HTTPS
   by itself: configure Orion's HTTPS listener or TLS termination at the proxy.
2. **Register a web application with the provider.** Use a confidential OIDC
   client with authorization code flow and a client secret. Register this exact
   redirect URI for the example address:

   ```text
   https://orion.example.test/api/auth/oidc/callback
   ```

   Allow `openid email profile` scopes, PKCE S256, and `client_secret_basic` or
   `client_secret_post` authentication. Orion currently accepts RS256-signed ID
   tokens. The ID token must include `sub`, `email`, and boolean
   `email_verified: true`; `given_name` and `family_name` are optional. Orion does
   not fetch missing email claims from the UserInfo endpoint.
3. **Save the provider in Orion.** Sign in as a system administrator and open
   **People → Organization sign-in**. Select the organization, choose **Add
   provider**, and enter a provider ID (such as `google`), issuer URL, client ID,
   and client secret. Select **Save provider**. The server encrypts the secret
   into that organization's `<secrets>` and saves the provider reference in the
   same configuration update. The saved secret is never returned to the browser.
   To edit a provider, select it in the same form. Leave **Client secret** empty
   to keep it, or enter a replacement to rotate it. Changing the issuer or client
   ID requires a secret for the new client. If another configuration change wins
   the race, use **Reload providers**, review the settings, and retry. Rotating a
   secret shared by other configuration entries gives this provider its own
   encrypted entry, leaving the other consumers unchanged.
4. **Configuration reference (optional).** The form writes this structure in
   the organization. For manual edits, `<oidc>` follows `<secrets>` and precedes
   `<invitations>`, if present. A Google provider looks like:

   ```xml
   <oidc>
     <provider id="google">
       <issuer>https://accounts.google.com</issuer>
       <clientId>YOUR_CLIENT_ID.apps.googleusercontent.com</clientId>
       <secret>google-client</secret>
     </provider>
   </oidc>
   ```

   Here `google-client` illustrates an existing encrypted secret ID; the UI generates
   its own secret ID. Keep the reference written by the form.
   For a corporate provider, use its issuer, client ID, and organization secret
   reference. The issuer must be an HTTPS URL without credentials, query, or
   fragment. Enter the issuer itself, not its discovery-document URL. Orion reads
   `ISSUER/.well-known/openid-configuration`; its `issuer` must match the configured
   value, and its authorization, token, and JWKS endpoints must use HTTPS.
5. **Test an invitation.** UI saves take effect immediately. If you edited XML
   manually, commit and push `orion.xml` to the configured configuration
   repository/ref; accepted updates reload the configuration. Open **People**,
   select the organization, enter the invitee's email, and copy the generated
   link. Open it in the browser that will complete sign-in, choose the provider,
   and sign in with that email. Complete the profile and check that only the
   organization's repositories are visible. Later, use **Sign in with OIDC**
   with the same organization; a new invitation is not required.

#### Provider registration examples

All examples assume Orion is available at `https://orion.example.com`:

| Setting | Value |
| --- | --- |
| Orion `system/https/publicUrl` | `https://orion.example.com` |
| Callback registered with every provider | `https://orion.example.com/api/auth/oidc/callback` |
| Requested scopes | `openid email profile` |

Replace example domains, realm names, and tenant IDs with your own values.
The callback always points to **Orion**; the issuer points to the **provider**.
Copy the issuer exactly from its discovery document, including a trailing slash
when present. These are registration examples, not a claim that every provider's
default token configuration satisfies Orion's requirements.

**Google / Google Workspace**

1. Open [Google Cloud credentials](https://console.cloud.google.com/apis/credentials)
   and select the project for Orion. Configure the OAuth consent screen/audience.
2. Create an OAuth client ID of type **Web application**, named `Orion`.
3. Add `https://orion.example.com/api/auth/oidc/callback` to **Authorized redirect
   URIs**. If the app is limited to test users, add the invited accounts.
4. Enter the client ID and client secret in **People → Organization sign-in**
   and save the provider.

Issuer: `https://accounts.google.com`

Discovery: `https://accounts.google.com/.well-known/openid-configuration`

See [Google's OIDC registration guide](https://developers.google.com/identity/openid-connect/openid-connect).

**Auth0**

1. Open the [Auth0 Dashboard](https://manage.auth0.com/), choose your tenant, then
   **Applications → Applications → Create Application**.
2. Name it `Orion` and choose **Regular Web Applications**.
3. In **Settings**, add `https://orion.example.com/api/auth/oidc/callback` to
   **Allowed Callback URLs**. Keep RS256 signing and use client secret basic or
   post authentication. Enable the connection users will sign in through.
4. Copy **Client ID** and **Client Secret**. Use the issuer for the same tenant
   domain used during login; users must have a verified email.

Example issuer: `https://YOUR_TENANT.eu.auth0.com/`

Discovery: `https://YOUR_TENANT.eu.auth0.com/.well-known/openid-configuration`

Use your actual domain; a custom Auth0 domain also changes the issuer.
See [Auth0 application settings](https://auth0.com/docs/get-started/applications/application-settings).

**Keycloak**

1. Open your deployment's admin console, for example
   `https://sso.example.com/admin/`, and select the realm, such as `employees`.
2. Under **Clients**, create an **OpenID Connect** client with client ID `orion`.
3. Enable **Client authentication** and **Standard flow**. Set **Valid redirect
   URIs** to `https://orion.example.com/api/auth/oidc/callback` and configure PKCE
   method **S256**. Copy the secret from **Credentials**.
4. Include the `email` and `profile` client scopes in ID tokens. Configure email
   verification so invited users receive `email_verified: true` after verification;
   do not replace verification with an unconditional claim.

Example issuer: `https://sso.example.com/realms/employees`

Discovery: `https://sso.example.com/realms/employees/.well-known/openid-configuration`

If Keycloak uses an additional public path prefix, include it in the issuer.
See [Keycloak client administration](https://www.keycloak.org/docs/latest/server_admin/)
and [OIDC endpoints](https://www.keycloak.org/securing-apps/oidc-layers).

**Microsoft Entra ID (Azure AD)**

1. Open the [Microsoft Entra admin center](https://entra.microsoft.com/), then
   **Entra ID → App registrations → New registration**.
2. Name the application `Orion` and choose a single tenant for the organization.
3. Under **Authentication**, add a **Web** platform with redirect URI
   `https://orion.example.com/api/auth/oidc/callback`.
4. Under **Certificates & secrets**, create a client secret. Copy its **Value**,
   not its secret ID, and the **Application (client) ID** from **Overview**.

Example issuer: `https://login.microsoftonline.com/YOUR_TENANT_ID/v2.0`

Discovery: `https://login.microsoftonline.com/YOUR_TENANT_ID/v2.0/.well-known/openid-configuration`

Use the directory's tenant GUID, not `common` or `organizations`: Orion expects
an exact issuer, not a tenant-dependent issuer template.

**Current compatibility limitation:** ordinary Entra workforce tokens do not
provide the verified-email guarantee required by this Orion flow. Adding an
`email` claim alone is insufficient; Orion rejects tokens without boolean
`email_verified: true`. Therefore this registration alone does not make direct
Entra login ready to use. It needs a separate verified-email onboarding design
or a broker that actually verifies email and issues compatible tokens. Do not
hard-code `email_verified` to bypass the check.

See [Entra app registration](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app),
[redirect URI configuration](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-redirect-uri),
and [ID token claims and email limitations](https://learn.microsoft.com/en-us/entra/identity-platform/id-token-claims-reference).

#### Troubleshooting provider setup

If setup fails, check:

- **Organization cannot be selected in People:** no OIDC provider is configured
  for it yet.
- **Invitation creation fails:** check the public HTTPS URL, email, and provider
  configuration; an existing user with that email cannot be invited again.
- **Provider rejects the redirect:** scheme, hostname, port, and callback path
  must exactly match the registered URI.
- **Orion rejects sign-in:** check the issuer and secret, token claims, invitation
  email and expiry, and that the callback opens in the same browser. Restart the
  login flow after changing provider configuration or restarting Orion.

### Invitations and account lifecycle

In the UI, a system administrator opens **People**, selects any organization
with OIDC configured, and enters the invitee's email. Orion returns a link to
copy and send; it does not send email. Invitations last seven days. Creating
another invitation for the same organization and email invalidates the previous
link. Only its SHA-256 digest is persisted under the organization's `<invitations>`.

The invitee opens the link, signs in with the organization's provider, and sets
first and last names. The verified email must match the invitation. Account
creation and invitation consumption are saved together with a configuration
revision check. The resulting `AccessControl.User` stores an `OIDC_SUBJECT`
credential binding the issuer and subject; later sign-ins use that binding.
New users receive read access to repositories in their own organization.
Organization users cannot access system administration.

Login attempts expire after ten minutes and are bound to the initiating browser;
a server restart requires restarting login, while unused invitations survive.
The one-hour access token is stored in the browser's session storage. The UI
renews it automatically shortly before expiry and before requests after the
computer wakes up. Renewal uses a separate `Secure`, `HttpOnly`, `SameSite=Strict`
cookie; JavaScript cannot read that credential. No additional provider settings
or provider refresh tokens are needed.

Each OIDC provider has its own session timeout policy, editable in **People →
Organization sign-in**. The UI accepts hours; XML stores seconds:

- `idleTimeoutSeconds`: inactivity limit, default `172800` (48 hours), greater than zero.
- `reauthenticationTimeoutSeconds`: maximum time since authentication, default `0`
  (disabled). Set `604800` for mandatory sign-in every seven days.

Both settings accept whole seconds up to `2147483647`. Omitting them in XML
uses the defaults. For example, add these after `<secret>` inside `<provider>`:

```xml
<idleTimeoutSeconds>172800</idleTimeoutSeconds>
<reauthenticationTimeoutSeconds>604800</reauthenticationTimeoutSeconds>
```

Each browser session keeps its own activity time. Authenticated requests to
working `/api/` endpoints extend inactivity, including background polling of
those endpoints. Authentication endpoints (`/api/auth/`), lifecycle health
checks, `/api/health`, `/api/ready`, `/api/live`, static files, `OPTIONS`, and
`HEAD` do not extend inactivity. Requests require a matching session cookie
and user identity; one user's activity cannot extend another session.

Token renewal itself does not extend inactivity. An expired session cannot be
revived by sending another request. Issued access tokens last at most one hour
and never outlive either current session deadline. With mandatory sign-in
configured, Orion requests fresh authentication using OIDC `max_age=0` and
requires a signed, recent `auth_time` claim; providers must support this
[OIDC behavior](https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest).
Activity never moves the mandatory sign-in deadline. Changing provider settings
invalidates existing sessions on their next activity or renewal.

Browser sessions survive restarts in `<baseDir>/oidc-sessions`, one encrypted file per session.
Orion reuses its persisted configuration encryption key with a separate session context bound to
that file's identifier. AES-GCM detects modified contents and records copied under another
identifier. Cookie secrets are not stored on disk. Session updates replace files atomically;
logout deletes the file before reporting success. Expired records are removed during authentication
and activity requests, including the first request after restart. Keep the data directory and key
material persistent. Run only one Orion process against a session directory.
Encryption does not prevent restoring an older valid file from backup, so restoring session files
can restore sessions that were subsequently signed out. Reloading the same browser tab preserves renewal
metadata; manually supplied Admin API tokens are not renewed.

**Sign out** revokes the browser session and removes the local access token.
Previously issued access tokens remain subject to their original expiry
(at most one hour). Removing the user, its OIDC binding, or the trusted issuer
invalidates its access tokens and prevents renewal. Changes to the session's provider
configuration or public origin also prevent renewal. Temporary network failures
retain a still-valid access token and retry renewal; an expired or revoked
session requires signing in again.

### HTTPS and ACME

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

### Local AgentD

After `make run-server` and the one-time `make enroll-admin-key`, start the
agent in another terminal:

```sh
make run-agent
```

The alias defaults `AGENT_ARGS` to `--allow-unsecure`, connecting to
`http://localhost:8000` over HTTP/2 without TLS. The launcher
obtains a fresh launch permit through the administrative `issue-launch-permit`
SSH command, using your enrolled key. AgentD state defaults to
`orion_root/agentd-local`. Use `AGENT_ARGS='--help'` to see endpoint, state,
label, and SSH options. When overriding `AGENT_ARGS` for HTTP, include
`--allow-unsecure` explicitly.

### AgentD control transport

AgentD opens its long-lived control stream at `POST /agent/control`. The
listener accepts this route over HTTP/2 on both HTTP and HTTPS connectors.
AgentD requires HTTPS unless `--allow-unsecure` explicitly permits HTTP; the
flag does not disable certificate validation for an HTTPS endpoint.
Set AgentD's `--server` to the absolute `publicUrl` origin (for example,
`https://orion.example.test:8443`); AgentD appends the control path.

The HTTPS identity named by `<identity>` must already exist in Orion's
protected material store. Its certificate chain must be trusted by the AgentD
JVM, and its subject alternative names must cover the host in `--server`.
AgentD currently authenticates with its one-time launch permit and subsequent
in-memory reconnect token, not a TLS client certificate. Keep
`<clientAuthentication>disabled</clientAuthentication>` (or `want` when other
clients use certificates); `required` rejects the current AgentD client before
the protocol handshake. Client trust anchors configure verification of TLS
client certificates and do not make AgentD trust Orion's server certificate.

Orion persists server-side agent and reconciled session records below
`<bootstrap.baseDir>/agent-session-server`. AgentD's separate `--state-dir`
holds its process lock and local session journals. Remote host provisioning,
SSH credential resolution, and packaged deployment are administered separately.

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
New configuration includes an empty organization with ID `default`; it does not
receive system administrator privileges. Existing configurations are preserved,
including those without `default`.
Accepted pushes to the configured ref reload the ACL; an invalid candidate
leaves the last valid ACL active. The internal repository is returned by
`GET /api/admin/repositories` together with user-created repositories.
Remote storage credentials, for example S3 credentials, should come from the
backend's normal environment or provider-specific mechanisms.

## Remote Git Proxy Endpoints

An active system proxy with alias `configuration` is available at
`https://<host>/r/proxy/system/configuration.git` and
`ssh://<user>@<host>:<ssh-port>/proxy/system/configuration.git`.
`GET /api/admin/proxies` reports the relative HTTP endpoint for each alias.

HTTP Git URLs require `.git` as the explicit repository boundary, for example
`/r/organization/team/project.git/info/refs`. The first `.git/` separates the repository name
from its operation; namespace segments therefore must not end in `.git` in HTTP URLs.
The `/r/` handler resolves the repository once, then dispatches exact child operations:
`info/refs`, `git-upload-pack`, `git-receive-pack`, or `objects/pack/<id>.pack`.
Each operation checks its allowed methods and repository permissions before reading a request body
or starting a successful response. URLs without the `.git` boundary are rejected; existing HTTP
remotes must include it. Advertised pack download URLs use the same boundary.

Grant repository access to `proxy/system/configuration` and the required branches
using the ordinary Git ACL rules. Push also requires `READ_WRITE` permission; force
updates require force permission. Repository and HTTP route patterns use the same slash-separated syntax.
All characters other than `*` are literal. `*` matches zero or more characters within one segment,
never `/`: `team*` matches `teamone`, but not `team/one`; `team/*` matches `team/one`, but not
`team/one/api`. A standalone `**` segment matches zero or more levels: `team/**` matches `team`
itself and every descendant, and `team/**/api` also matches `team/api`. Embedded `**`, such as
`team**`, is invalid and does not match. Separators are literal: `team/*` can match `team/`,
but not `team`; the resource boundary still validates actual repository names and request paths.

Existing repository ACL expressions that intentionally cover nested paths must change from `team/*`
to `team/**`, and global repository grants from `*` to `**`. There is no legacy matching mode or
automatic rewrite of stored ACLs. `**` includes paths such as `proxy/system/configuration`.
Application admin permission alone does not grant Git access. The separate `BRANCH=*` rule
continues to mean all branches, including names containing `/`.

Reads refresh from upstream, and a push succeeds only after upstream publication
succeeds. Aliases remain stable across restart. The `proxy/system/` namespace is
reserved for configured proxies: pushing to an unknown alias cannot create a
local repository, and activation rejects an existing local repository at the
same name. Private `bootstrap/proxy-*` cache names are never public Git endpoints,
including for administrators with matching repository grants.

Repository ACL grants use `READ_WRITE` for combined read/write access. The admin user API
accepts `readWrite: true` in each repository grant. Existing ACL XML must replace the old
`WRITE` key with `READ_WRITE`, and API clients must replace the `write` field with `readWrite`.
For push, branch restrictions are taken only from matching `READ_WRITE` grants; read-only
grants cannot expand or restrict writable branches. Among matching `READ_WRITE` grants,
the existing combination policy remains: if any specify branches, those branches form
the allowed set, and a `BRANCH=*` grant permits all branches.

## Development

Run routine local tests with the `dev` Maven profile:

```sh
mvn package -Pdev
```

Run the standard development verification:

```sh
mvn verify -Pdev
```

Build caching is enabled for Maven package builds. The Make test goals run through
`package` so cached reactor dependencies are available as JARs. Unchanged modules can reuse a
successful test result; changing sources, resources, or dependencies invalidates
the affected cache entries. Test selectors, skip flags, and the matrix control
switch are also checked.

To force fresh verification, including after changing external Git/SSH tools or
the JDK, bypass the cache:

```sh
make test MAVEN='mvn -Dmaven.build.cache.enabled=false'
```

The same override works with `make run-test`. To try the installed Maven daemon,
pass `MAVEN='mvnd --batch-mode'`; Maven remains the default.

Unit tests use normal log levels by default. Enable project DEBUG logging for a
local test run with:

```sh
mvn package -Pdev -Dorion.test.debug=true
```

Tune the test log level and categories when needed:

```sh
mvn package -Pdev \
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
