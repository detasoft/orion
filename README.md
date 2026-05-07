# Orion

Lightweight GIT hosting solution.

# Starting the server

`java pro.deta.orion.App`

# Configuration

pro.deta.orion.config.FileConfigurationProviderImpl.CONFIGURATION_LOCATION

# Self-Hosted ACL Git Storage Bootstrap Flow

The ACL service and the Git-backed ACL storage depend on a strict startup order. This is the self-hosted bootstrap path:
Orion can store its ACL document in its own Git repository and read that configuration through its own local Git
transport during startup.

That creates a bootstrap loop:

1. To load ACL from Git, Orion needs permission to access the Git repository.
2. To know who has permission, Orion needs the ACL document.
3. But the ACL document is the thing Orion is trying to read from Git.

The startup flow breaks that loop with a temporary in-memory user:

The scenario is:

1. `OrionAccessControlServiceImpl` runs during the `INIT` stage with
   `INIT_PRIORITY` and `waitForCompletion()`.
2. During that step it registers event handlers for `VolatileUserAdded` and
   `RequestToAclUpdate`.
3. `GitAccessControlStorage` runs later in the same `INIT` stage with
   `INIT_PRIORITY + 1`.
4. It registers the ACL storage area in `GitBackedInternalStorage`.
5. `GitBackedInternalStorage` creates a volatile local user for that storage
   area, normally `orion_acl`, and publishes `VolatileUserAdded`.
6. Because the ACL service handler was registered first, the volatile user is
   added to the in-memory ACL before the `STARTING` stage loads or creates the ACL document from Git.
7. During `STARTING`, `GitBackedInternalStorage` starts at
   `GIT_BACKED_INTERNAL_STORAGE_PRIORITY`, and `OrionAccessControlServiceImpl`
   starts at `GIT_BACKED_INTERNAL_STORAGE_PRIORITY + 2`.

Do not remove the `INIT` priorities or `waitForCompletion()` calls without
re-checking this flow. If the volatile user event is published before the ACL
service subscribes to it, Orion can fail to read its own Git-backed ACL configuration during startup.

# Target ACL Bootstrap Flow

The target startup model should remove the self-transport bootstrap loop. Local deployment configuration should have
three root sections:

1. `bootstrap`: server startup settings and path to ACL.
2. `storage`: physical repository storage and repository creation policy.
3. `transport`: network transports.

Startup order should be:

1. Read local deployment configuration.
2. Configure `storage`.
3. Configure `bootstrap.accessControl`.
4. Load ACL through the configured ACL storage.
5. Start `transport` only after storage and ACL are ready.
6. Accept external users through normal ACL checks.

`transport` must not participate in ACL bootstrap. For `git+storage:` ACL, Orion opens the configured repository through
the internal storage backend directly. Local filesystem storage normally needs no backend authorization; S3 or another
remote backend may need backend credentials from environment variables, files or provider-specific mechanisms. Those
backend credentials are not Orion ACL users.

# Next steps
1. [git-mirror] func to mirror github/gitlab hostings
2. [ACL] [ssh-remote] add support for user's key provided
3. [ACL] [https-remote] [file-remote] [hsql-remote]
2. User's authorization switch to oauth (should support application auth and token auth)
3. Rest API for starting up the frontend
4. Exec builds / agents [dynamic-builds??] [estimate capability] [schedule build] [run multiple per agent] [github-runner compatible] 
5. Build agents installable via ssh / contact via plain socket
5. Serve maven repository [http,https]
6. let's encrypt integration [initial cert request, scheduled cert renewal]
7. dynamic domain allocation (using deta.pro)

Postponed
1. Switch [GitNativeTransportService.java](net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java) to NIO - doesn't make sense as still a single thread needed to serve native protocol.
In the future if rest via netty be adapted - it would be useful to switch it to a new . 
