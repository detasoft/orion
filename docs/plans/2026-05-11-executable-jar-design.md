# Executable Jar Packaging Design

## Goal

Make Orion produce runnable distribution artifacts from the `core/bootstrap`
module: one jar for `java -jar` and one fully executable Unix jar suitable for
direct execution and init.d-style service installation.

## Recommended Approach

Use `spring-boot-maven-plugin` as a packaging tool only. Orion will not add
Spring Framework or Spring Boot runtime dependencies. The plugin will repackage
the existing `core/bootstrap` jar around the existing application entry point,
`pro.deta.orion.App`.

The build should attach two additional artifacts:

- `bootstrap-<version>-app.jar`: executable with `java -jar`.
- `bootstrap-<version>-initd.jar`: fully executable jar with an embedded Unix
  launch script.

Keeping separate classifiers avoids the known tradeoff of script-prepended jars:
some jar tools do not handle them like normal archives. Users who want standard
jar behavior can use the `app` classifier, while Linux init.d deployments can
use the `initd` classifier.

## Build Behavior

The packaging should live in `core/bootstrap/pom.xml`, because that module owns
the `pro.deta.orion.App` main class and already depends on the runtime modules
needed to start the server.

The root Maven build should continue to build all modules normally. Packaging
`core/bootstrap` should create the normal thin jar plus the two attached
distribution jars.

## Documentation

Update the README with:

- `mvn package -Pdev -pl core/bootstrap -am`
- `java -jar core/bootstrap/target/bootstrap-<version>-app.jar`
- executable jar usage with `chmod +x`
- init.d symlink example

## Verification

Run a focused package build:

```sh
mvn package -Pdev -pl core/bootstrap -am
```

Then check that both classified jars exist and that the init.d jar starts with
a shell script marker. Full test verification can remain the routine project
command:

```sh
mvn test -Pdev
```
