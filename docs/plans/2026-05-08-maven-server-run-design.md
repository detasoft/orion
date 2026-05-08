# Maven Server Run Design

**Context**

Orion already has a Java entry point at `pro.deta.orion.App` in `core/bootstrap`, but the repository does not expose a Maven-native way to start the server. The current README instructs users to invoke the application class manually.

**Decision**

Add a `run-server` Maven profile to `core/bootstrap/pom.xml` and bind `exec-maven-plugin` to the `process-classes` phase inside that profile. This keeps the change local to the module that owns `App`, avoids packaging changes, and enables a standard command:

```bash
mvn -pl core/bootstrap -am -Prun-server process-classes
```

Add a root `Makefile` with a convenience target that delegates to the Maven command. This gives a short project-level entry point while keeping Maven as the underlying execution mechanism.

**Rejected Alternatives**

1. Add a root-POM alias only.
This hides where the entry point lives and adds indirection without solving anything the module-local plugin configuration cannot solve.

2. Build a runnable jar with `Main-Class`.
Useful for distribution, but not necessary for the requested "run directly from Maven" workflow.

**Configuration**

No application code changes are required. Runtime configuration remains file-based, with `config.toml` and `config.yml` in the working directory still taking precedence.

**Verification**

1. `mvn -pl core/bootstrap -am -Prun-server process-classes` should resolve reactor dependencies, compile the bootstrap module, and invoke the configured main class through the profile-bound execution.
2. `make run-server` should delegate to the same Maven command.
3. README should document both commands.
