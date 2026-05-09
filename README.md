# Orion

Lightweight GIT hosting solution.

# Starting the server

`mvn -pl core/bootstrap -am -Prun-server process-classes`

or

`make run-server`

# Configuration

Orion reads local configuration from `config.toml`, `config.yml`, `/etc/orion/orion.yml`, then bundled classpath
defaults. The active configuration shape has three top-level sections:

```yaml
bootstrap:
  baseDir: /var/lib/orion
  workDir: work
  threadPoolSize: 10
  accessControl:
    location: local:orion
    branch: master
    paths:
      - orion.xml
    createDefaultIfMissing: true
storage:
  location: file:/var/lib/orion/repos
  createOnPush: true
transport:
  defaultAddress: localhost
  git:
    enabled: false
    port: 9418
  ssh:
    enabled: true
    port: 8022
  http:
    enabled: true
    port: 8000
  https:
    enabled: false
    port: 8443
```

# ACL Bootstrap Flow

The ACL startup path does not use Orion network transports to read ACL. For repository-backed ACL locations such as
`local:orion`, Orion opens the repository storage directly and reads the configured ACL files from the requested
branch. For local filesystem ACL files, use a `file:` location pointing at the ACL directory. There is no temporary
Orion user for ACL bootstrap.

The target deployment configuration should have three root sections, but their startup roles are different:

1. `bootstrap`: server startup settings and path to ACL.
2. `storage`: physical repository storage and repository creation policy; its provider must be available before ACL
   loads because ACL can be stored in a repository.
3. `transport`: network transports; they start only after ACL has loaded.

Startup order should be:

1. Read local deployment configuration.
2. Configure `storage` and make the selected storage provider available.
3. Configure `bootstrap.accessControl`.
4. Load ACL through the configured ACL storage, possibly opening a repository through `storage`.
5. Start `transport` only after ACL is loaded.
6. Accept external users through normal ACL checks.

`transport` must not participate in ACL bootstrap. For `local:` ACL, Orion opens the configured repository through
the internal storage backend directly. Local filesystem storage normally needs no backend authorization; S3 or another
remote backend may need backend credentials from environment variables, files or provider-specific mechanisms. Those
backend credentials are not Orion ACL users.

# Unit test logging

Unit tests run with normal log levels by default. To enable project DEBUG logs for a local test run:

`mvn test -Pdev -Dorion.test.debug=true`

The default debug category is `pro.deta.orion`. Use `orion.test.log.level` and `orion.test.log.categories` for a
narrower or broader scope:

`mvn test -Pdev -Dorion.test.debug=true -Dorion.test.log.level=TRACE -Dorion.test.log.categories=pro.deta.orion.git,org.eclipse.jgit=WARN`

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
