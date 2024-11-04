# Orion

Lightweight GIT hosting solution.

# Starting the server

`java pro.deta.orion.App`

# Configuration

pro.deta.orion.config.FileConfigurationProviderImpl.CONFIGURATION_LOCATION

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
1. Switch [GitNativeTransportService.java](git-engine/src/main/java/pro/deta/orion/git/GitNativeTransportService.java) to NIO - doesn't make sense as still a single thread needed to serve native protocol.
In the future if rest via netty be adapted - it would be useful to switch it to a new . 
