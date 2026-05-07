# Orion

Lightweight GIT hosting solution.

# Starting the server

`java pro.deta.orion.App`

# Configuration

pro.deta.orion.config.FileConfigurationProviderImpl.CONFIGURATION_LOCATION

# ACL Git Storage Bootstrap Event Flow

The ACL service and the Git-backed ACL storage depend on a strict startup order.
The order is important because the Git-backed storage may publish a temporary
user before the ACL document has been loaded from Git.

The scenario is:

1. `OrionAccessControlServiceImpl` runs during the `INIT` stage with
   `INIT_PRIORITY` and `waitForCompletion()`.
2. During that step it registers event handlers for `VolatileUserAdded` and
   `RequestToAclUpdate`.
3. `GitAccessControlStorage` runs later in the same `INIT` stage with
   `INIT_PRIORITY + 1`.
4. It registers the ACL storage area in `GitBackedInternalStorage`.
5. `GitBackedInternalStorage` creates a volatile local user for that storage
   area and publishes `VolatileUserAdded`.
6. Because the ACL service handler was registered first, the volatile user is
   added to the in-memory ACL before the `STARTING` stage loads or creates the
   ACL document.
7. During `STARTING`, `GitBackedInternalStorage` starts at
   `GIT_BACKED_INTERNAL_STORAGE_PRIORITY`, and `OrionAccessControlServiceImpl`
   starts at `GIT_BACKED_INTERNAL_STORAGE_PRIORITY + 2`.

Do not remove the `INIT` priorities or `waitForCompletion()` calls without
re-checking this flow. If the volatile user event is published before the ACL
service subscribes to it, local Git-backed ACL access can fail during startup.

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
