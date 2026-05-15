# Executable Jar Packaging Design

## Goal

Make Orion produce runnable distribution artifacts from the `core/bootstrap`
module: one regular shaded jar for `java -jar` and one fully executable Unix
jar suitable for direct execution and init.d-style service installation.

## Recommended Approach

Use `maven-shade-plugin` to build the runnable `all` jar, then prepend a small
POSIX/LSB launcher with `maven-antrun-plugin`. Orion will not add Spring
Framework or Spring Boot runtime dependencies.

The build should attach one self-executable artifact:

- `bootstrap-<version>-executable.jar`: fully executable jar with an embedded
  LSB launch script.

The same `executable` artifact is used for direct execution and init.d
installation. Keeping a second Maven artifact with identical bytes would add
publishing noise without changing runtime behavior.

The shell launcher should keep the LSB header and resolve the current jar path,
but it should not own service process management. It should pass all command
line arguments to the JVM. Java code should handle `run`, `start`, `stop`,
`status`, `restart`, and `verify`.

## Build Behavior

The packaging should live in `core/bootstrap/pom.xml`, because that module owns
the `pro.deta.orion.App` main class and already depends on the runtime modules
needed to start the server.

The root Maven build should continue to build all modules normally. Packaging
`core/bootstrap` should create the normal thin jar, the `all` shaded jar, and
the attached `executable` distribution jar.

## Documentation

Update the README with:

- `mvn package -Pdev -pl core/bootstrap -am`
- `java -jar core/bootstrap/target/bootstrap-<version>-all.jar`
- executable jar usage with `chmod +x`
- init.d symlink example

## Verification

Run a focused package build:

```sh
mvn package -Pdev -pl core/bootstrap -am
```

Then check that the shaded jar and executable jar exist and that the executable
jar starts with a shell script marker. Full test verification can remain the
routine project command:

```sh
mvn test -Pdev
```
