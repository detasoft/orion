# Align Remote Configuration Tests With Bootstrap Inputs

Status: todo
Depends on: existing native bootstrap/provider and transport foundations.

## Current mismatch

`LocationConfigurationProviderIT` calls `LocationConfigurationProvider` with
`git+http/ssh` URLs to a process YAML file. Its production readers support local,
classpath, and S3 input, not Git. Remote bootstrap instead loads `orion.xml` and
material from descriptors in process TOML/YAML. Changing only the test server
cannot reconcile these different contracts.

## Requirements and design

Preserve meaningful HTTP/SSH configuration coverage using the supported startup
contract: read process configuration, resolve remote `orion.xml`/material through
`BootstrapContext`, then assert the resulting runtime configuration/ACL.
Do not restore a Git reader for process YAML merely to satisfy legacy tests.
Keep file/classpath/S3 process configuration behavior and tests independent.

## Implementation plan

1. Replace the two stale remote-process-YAML scenarios with remote-bootstrap
   tests and document the supported source-descriptor configuration in their
   fixture setup.
2. Seed a second native Orion runtime through supported APIs, with deterministic
   local HTTP/SSH endpoints and test credentials outside URIs.
3. Share fixture support with [06/04](04_native-integration-test-migration.md);
   preserve authentication and seeded-configuration assertions.
4. Cover a successful launch and a rejected remote source before target public
   transports start. Avoid waiting for persistent adoption or admin UI work.

## Acceptance

Both transports load the seeded configuration through the actual bootstrap
contract. Tests no longer require removed readers, JGit layout, or secret query
parameters. Verify the affected integration scenarios and
`mvn verify -Pdev -T 4`; classify unrelated failures separately.
