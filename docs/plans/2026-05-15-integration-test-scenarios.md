# Integration Test Scenarios

## Current Coverage

1. `LocationConfigurationProviderIT`
   - Reads `orion.yml` from Git HTTP, Git SSH, and S3/MinIO.
   - Flow: seed configuration repository or object, start the test Git/MinIO backend, call `LocationConfigurationProvider`, verify runtime configuration fields.

2. `OrionStartupIT`
   - Fresh start creates storage and ACL.
   - Restart does not rewrite ACL.
   - Generated ACL validates against the published schema.
   - Remote Git ACL load/save.
   - S3 ACL load/save through MinIO.
   - Orion shutdown does not break standalone JGit push in the same JVM.
   - HTTP shutdown completes promptly.

3. `GitSshTransportEndToEndIT`
   - Authorized push/clone/pull over SSH.
   - Create repository, push, and fetch over SSH.
   - Unknown SSH key denied.
   - Managed user created through admin HTTP survives restart.
   - Root issues a bearer token over SSH command.
   - SSH shutdown command stops the server.
   - Component restart in the same JVM followed by SSH Git operations.
   - Same SSH E2E scenario runs twice in one JVM.

4. S3 Git repository integration tests
   - Active: configured bucket exists.
   - Disabled: first push/fetch through direct S3 repository and S3 NIO provider.

5. `OrionDTLSOpenSSLIT`
   - Starts Netty DTLS UDP server, connects with external `openssl s_client -dtls`, verifies handshake and echo response.

6. `IntegrationCloudflareIT`
   - Skipped unless `CLOUDFLARE_API_TOKEN` is present.
   - Performs real Cloudflare DNS side effects for `jump.deta.pro`.

## Recommended Scenarios

1. Runtime HTTP admin API contract.
   - Start a real `OrionComponent`.
   - Obtain a bearer token through `POST /api/admin/token`.
   - Verify `/api/admin/acl` without token returns `403`.
   - Verify `/api/admin/acl` with bearer token returns `200` and `application/xml`.
   - Verify `/api/admin/routes` with admin token returns the runtime route table.
   - Verify wrong method returns `405`.
   - Verify unknown path returns `404`.
   - Verify invalid bearer token returns `403`.

2. ACL update through HTTP reloads runtime ACL.
   - Start server with default ACL.
   - `GET /api/admin/acl`.
   - Modify XML and `POST /api/admin/acl`.
   - Verify the new user can authenticate or use SSH Git immediately.
   - Verify removed users no longer authenticate.
   - Restart server and verify the updated ACL is still active.
   - Negative case: invalid XML returns `400`, active ACL and stored ACL stay unchanged.

3. SSH Git authenticated but unauthorized cases.
   - A read-only user can clone/fetch but cannot push.
   - A user without `CREATE` cannot create a repository with the first push.
   - A branch-restricted reader cannot fetch a branch outside the grant.

4. SSH-issued token used against HTTP API.
   - Use `ssh root issue-token 3600`.
   - Call `GET /api/admin/acl` with `Authorization: Bearer <token>`.
   - This verifies server identity key authentication, SSH command handling, JWT issue, HTTP bearer authentication, and admin route authorization in one flow.

5. Remote ACL storage through real Git HTTP or SSH.
   - Start test Git HTTP or SSH backend.
   - Configure ACL location as `git+http` or `git+ssh`.
   - Load ACL, update it through HTTP admin API, verify push to remote, restart, verify updated ACL is loaded.

6. S3 ACL through HTTP API and restart.
   - Start MinIO.
   - Configure ACL location as S3.
   - Update ACL through `POST /api/admin/acl`.
   - Verify the S3 object is updated.
   - Restart and verify the updated ACL is active.

7. HTTP Git route or native Git transport.
   - If `/r/*` and native `git://` are intended public contracts, add at least one push/fetch/clone scenario for each active transport.
   - SSH Git is well covered; these transports currently have much weaker integration coverage.

8. ACME API through runtime without live Let's Encrypt.
   - Use a fake ACME client/server.
   - Admin token calls `POST /api/admin/acme/certificate`.
   - HTTP-01 challenge becomes available through `/.well-known/acme-challenge/*`.
   - Route returns nginx PEM.
   - Persisted certificate can be downloaded with `GET /api/admin/acme/certificate`.

## Notes

- Do not expand live Cloudflare tests into mandatory CI scenarios; keep them opt-in.
- Avoid duplicating `LocationConfigurationProvider` integration coverage for simple file/classpath/TOML/YAML cases. Unit tests already cover those paths well enough.
