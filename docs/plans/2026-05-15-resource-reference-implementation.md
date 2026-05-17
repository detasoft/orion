# Resource Reference Model Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the current resource expression prototype with a named, AST-based resource reference model that supports shell-style variables, defaults, required references, document dereferencing, capability-based resolution, and extension resolvers.

**Architecture:** Keep `core/resource-addressing` as the implementation module and expose the model from `pro.deta.orion.resource.reference`. The parser builds a typed AST and the resolver evaluates that AST against an immutable scope and an explicit resolver registry; it must not flatten the input into one string and then rediscover boundaries.

**Tech Stack:** Java 21, Maven, ANTLR4-generated parser classes, JUnit 5, AssertJ, Jackson YAML/XML, toml4j, Java `HttpClient`, lightweight reader interfaces for S3 and Git.

---

## Current Constraint

`core/resource-addressing` is already included in `core/pom.xml`, so the first red tests will make the normal reactor red. This is accepted for this work. Do not remove the module from the reactor, and do not add new reactor wiring.

During the red phase, use targeted commands and expect failure:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceParserTest
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceResolverTest
```

The final state of this plan must restore:

```bash
mvn test -Pdev -pl core/resource-addressing
mvn test -Pdev
```

## Grammar Source And Generated Parser

The grammar must live as a first-class module resource:

```text
core/resource-addressing/src/main/resources/pro/deta/orion/resource/reference/ResourceReference.g4
```

This grammar is the parser source of truth. Do not duplicate the grammar as
Java string constants or inline parser tables. The generated ANTLR classes must
be written under:

```text
core/resource-addressing/target/generated-sources/resource-reference
```

Generated classes are build output and must not be committed. Hand-written AST,
resolver, and public API classes remain under:

```text
core/resource-addressing/src/main/java/pro/deta/orion/resource/reference
```

Use the generated classes only behind `ResourceReferenceParser`. Keep the public
API names independent from generated ANTLR names so grammar changes do not leak
through the module boundary.

The Maven generation should use `exec-maven-plugin` in `generate-sources` to run
`org.antlr.v4.Tool`, then `build-helper-maven-plugin` to add the generated
source directory. Prefer this to checking generated files into git.

## Naming Decisions

Use these names unless implementation proves a concrete problem:

| Concept | Name | Rationale |
| --- | --- | --- |
| Raw user/config syntax plus parsed AST | `ResourceReference` | "Reference" says the value can contain indirection; it is not necessarily a concrete address. |
| Concrete scheme-bearing resource locator | `ResourceAddress` | Keep this for already resolved locations such as `file:/x`, `s3://bucket/key`, or `git+https://...`. |
| Resource scheme value | `ResourceScheme` | Existing name is good and should stay shared. |
| Immutable bytes/text snapshot | `ResourceContent` | Existing name is clear and matches capability resolution. |
| Public resolver facade | `ResourceReferenceResolver` | Distinguishes reference evaluation from low-level address/capability conversion. |
| Resolver input values | `ResourceReferenceScope` | "Scope" is clearer than "context" for environment and configuration variables. |
| Extension registry | `ResourceResolverRegistry` | Explicitly owns resolver selection and ambiguity checks. |
| Extension point for target types | `ResourceCapabilityResolver<T>` | Call sites ask for capabilities, not always addresses. |
| Detailed result with diagnostics | `ResourceResolution<T>` | Carries original reference, resolved value, and safe display text. |
| Internal AST root | `ReferenceNode` | Internal/parser-level name; avoids leaking "expression" into the public API. |
| Literal AST node | `LiteralReferenceNode` | Plain text segment or complete literal. |
| Variable AST node | `VariableReferenceNode` | Represents `$NAME`, `${NAME}`, and `${bootstrap.baseDir}`. |
| Interpolation AST node | `InterpolatedReferenceNode` | Represents compound values like `$ORION_ROOT/$PATH_TO_CONFIG`. |
| Address AST node | `AddressReferenceNode` | Represents explicit address syntax such as `file:...` or `s3://...`. |
| Document AST node | `DocumentReferenceNode` | Represents `${yaml:...}`, `${toml:...}`, and `${xml:...}`. |
| Parameter operator enum | `ParameterExpansionOperator` | Matches shell terminology without importing shell mutability semantics. |
| Document format enum | `DocumentFormat` | Values: `YAML`, `TOML`, `XML`. |
| Path inside a parsed document | `DocumentPath` | Avoids promising exact JSON Pointer semantics. |

All new tests should target the names above.

## Syntax Baseline

Supported variable forms:

```text
$ORION_ROOT
${ORION_ROOT}
${bootstrap.baseDir}
${ORION_ROOT:-orion_root}
${ORION_ROOT-orion_root}
${ORION_ROOT:?ORION_ROOT is required}
${ORION_ROOT?ORION_ROOT is required}
```

Compound paths:

```text
$ORION_ROOT/orion.xml
$ORION_ROOT/$PATH_TO_CONFIG
${ORION_ROOT:-orion_root}/orion.xml
${bootstrap.baseDir}/orion.xml
```

Document references:

```text
${yaml:${ORION_CONFIG_DATA}/bootstrap/baseDir}
${yaml:$ORION_CONFIG_DATA/bootstrap/baseDir}
${yaml:${ORION_CONFIG_PATH:-config.yml}/bootstrap/baseDir}
${toml:${ORION_CONFIG_PATH:-config.toml}/bootstrap/baseDir}
${xml:${ORION_ACL_PATH:-orion.xml}/accessControl/users/root}
```

Document source rules for this implementation:

- The source must be a nested `${...}` reference or a simple `$NAME` shorthand.
- Inline document text directly inside `${yaml:...}` or `${xml:...}` is rejected.
- Direct explicit-scheme document sources without a nested boundary are not implemented in this pass because `s3://bucket/key/path` and document paths are ambiguous. Use nested source syntax instead.
- A resolved source that is `ResourceContent` is parsed directly.
- A resolved source that is a `Path`, valid URI, or registered address is read as `ResourceContent`.
- A scalar that clearly looks like inline content, for example it contains a newline, starts with `{`, `[`, or `<`, is parsed as inline content.
- A scalar that looks like a path or URI but cannot be read fails; it must not be silently parsed as inline content.

Variable lookup rules:

- Dotted names are context references: `${bootstrap.baseDir}`.
- Simple names first check environment, then context: `$CONFIG_DATA`, `${CONFIG_DATA}`.
- `$NAME` shorthand matches `[A-Za-z_][A-Za-z0-9_]*` only. Defaults, required operators, dotted names, and nested references require `${...}`.
- Context values may reference other context values or themselves through the resolver; direct and indirect cycles fail with a diagnostic that names the cycle without printing secret values.

Parameter expansion operators:

| Syntax | Behavior |
| --- | --- |
| `${NAME:-word}` | Use `word` when `NAME` is unset or blank. |
| `${NAME-word}` | Use `word` when `NAME` is unset. Blank is a value. |
| `${NAME:?message}` | Fail with `message` when `NAME` is unset or blank. |
| `${NAME?message}` | Fail with `message` when `NAME` is unset. Blank is a value. |

`word` is parsed as a reference fragment, so defaults may contain nested interpolation.

## Grammar

This EBNF describes the same grammar as `ResourceReference.g4` and exists for
review readability. The `.g4` file is the build-time parser source of truth. The
grammar is intentionally stricter than the old `file:env:NAME` /
`scheme(inner)` prototype.

```ebnf
reference             = address-reference | interpolation ;

interpolation         = interpolation-part , { interpolation-part } ;
interpolation-part    = braced-reference
                      | shorthand-variable
                      | literal ;

braced-reference      = "${" , braced-body , "}" ;
braced-body           = document-reference
                      | variable-expansion
                      | address-reference ;

variable-expansion    = variable-name , [ parameter-expansion ] ;
parameter-expansion   = default-if-unset-or-blank
                      | default-if-unset
                      | error-if-unset-or-blank
                      | error-if-unset ;
default-if-unset-or-blank = ":-" , word ;
default-if-unset      = "-" , word ;
error-if-unset-or-blank = ":?" , word ;
error-if-unset        = "?" , word ;

word                  = interpolation-fragment-until-current-brace ;

document-reference    = document-format , ":" , document-source , document-path ;
document-format       = "yaml" | "toml" | "xml" ;
document-source       = braced-reference | shorthand-variable ;
document-path         = "/" , document-segment , { "/" , document-segment } ;

address-reference     = scheme , ":" , address-body ;
address-body          = interpolation-fragment ;

shorthand-variable    = "$" , simple-name ;
variable-name         = simple-name | context-name ;
context-name          = simple-name , "." , simple-name , { "." , simple-name } ;
simple-name           = name-start , { name-char } ;
scheme                = scheme-start , { scheme-char } ;

name-start            = "A"..."Z" | "a"..."z" | "_" ;
name-char             = name-start | "0"..."9" ;
scheme-start          = "A"..."Z" | "a"..."z" ;
scheme-char           = scheme-start | "0"..."9" | "+" | "." | "-" ;

literal               = any character sequence that does not start a valid
                        braced-reference or shorthand-variable ;
document-segment      = one or more characters except "/" and "}" ;
```

Parser precedence inside `${...}`:

1. `yaml:`, `toml:`, and `xml:` start a `DocumentReferenceNode`.
2. `NAME`, `NAME:-word`, `NAME-word`, `NAME:?word`, and `NAME?word` start a
   `VariableReferenceNode`.
3. `scheme:...` starts an `AddressReferenceNode`.

This precedence is required so `${ORION_ROOT:-orion_root}` is a variable
expansion, not an address with scheme `ORION_ROOT`.

Grammar examples:

```text
$ORION_ROOT                                  -> VariableReferenceNode
${ORION_ROOT:-orion_root}                   -> VariableReferenceNode
${bootstrap.baseDir:-orion_root}            -> VariableReferenceNode
$ORION_ROOT/$PATH_TO_CONFIG                 -> InterpolatedReferenceNode
file:${ORION_CONFIG:-config.yml}            -> AddressReferenceNode(file)
s3://${CONFIG_BUCKET}/orion.yml             -> AddressReferenceNode(s3)
${yaml:$ORION_CONFIG_DATA/bootstrap/baseDir} -> DocumentReferenceNode(yaml)
${yaml:${ORION_CONFIG_PATH:-config.yml}/bootstrap/baseDir}
${yaml:${file:${ORION_CONFIG_PATH:-config.yml}}/bootstrap/baseDir}
```

Rejected by grammar:

```text
${yaml:bootstrap/baseDir}            # document source is not nested or $NAME
${yaml:file:${ORION_CONFIG}/baseDir} # explicit source scheme must be nested
${ORION_ROOT:=orion_root}            # assignment operators are unsupported
${ORION_ROOT=orion_root}             # assignment operators are unsupported
$bootstrap.baseDir                   # shorthand supports simple names only
```

Document sources deliberately use `braced-reference` or `$NAME` only. If the
source itself is an explicit address, wrap it:

```text
${yaml:${file:${ORION_CONFIG_PATH:-config.yml}}/bootstrap/baseDir}
```

This keeps the AST boundary between `source` and `document-path` explicit and
avoids guessing whether a slash belongs to a URL/path or to the document path.

---

### Task 1: Add Grammar Resource And Generated Parser Wiring

**Files:**
- Create: `core/resource-addressing/src/main/resources/pro/deta/orion/resource/reference/ResourceReference.g4`
- Modify: `core/resource-addressing/pom.xml`

**Step 1: Add the grammar resource**

Create `ResourceReference.g4` in module resources. The grammar name must be
`ResourceReference`, and generated classes must be placed in package:

```java
pro.deta.orion.resource.reference.generated
```

The generated package is intentionally separate from the public
`pro.deta.orion.resource.reference` API.

**Step 2: Add ANTLR runtime dependency**

Add a module-local version property and dependency:

```xml
<properties>
    <antlr4.version>4.13.2</antlr4.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.antlr</groupId>
        <artifactId>antlr4-runtime</artifactId>
        <version>${antlr4.version}</version>
    </dependency>
</dependencies>
```

Keep existing dependencies. Do not add generated classes to source control.

**Step 3: Add exec generation**

Add `exec-maven-plugin` to `generate-sources`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <id>generate-resource-reference-parser</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>java</goal>
            </goals>
            <configuration>
                <mainClass>org.antlr.v4.Tool</mainClass>
                <includePluginDependencies>true</includePluginDependencies>
                <arguments>
                    <argument>-visitor</argument>
                    <argument>-no-listener</argument>
                    <argument>-Xexact-output-dir</argument>
                    <argument>-o</argument>
                    <argument>${project.build.directory}/generated-sources/resource-reference/pro/deta/orion/resource/reference/generated</argument>
                    <argument>${project.basedir}/src/main/resources/pro/deta/orion/resource/reference/ResourceReference.g4</argument>
                </arguments>
            </configuration>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>org.antlr</groupId>
            <artifactId>antlr4</artifactId>
            <version>${antlr4.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

**Step 4: Add generated source root**

Add `build-helper-maven-plugin` in `generate-sources`:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>add-resource-reference-generated-sources</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>add-source</goal>
            </goals>
            <configuration>
                <sources>
                    <source>${project.build.directory}/generated-sources/resource-reference</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Step 5: Generate classes and confirm compile-red state**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -DskipTests
```

Expected: generated ANTLR classes appear under
`core/resource-addressing/target/generated-sources/resource-reference`, and
main sources compile. Tests may still be red later because no public
`ResourceReference` API has been added yet.

**Step 6: Commit grammar and build wiring**

```bash
git add core/resource-addressing/pom.xml \
        core/resource-addressing/src/main/resources/pro/deta/orion/resource/reference/ResourceReference.g4
git commit -m "build: generate resource reference parser"
```

### Task 2: Add Red Parser Tests For New Names And Syntax

**Files:**
- Create: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceParserTest.java`

**Step 1: Write the failing parser tests**

Create `ResourceReferenceParserTest` with tests like:

```java
package pro.deta.orion.resource.reference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceReferenceParserTest {
    @Test
    void parsesSimpleEnvironmentReference() {
        ResourceReference reference = ResourceReference.parse("${ORION_ROOT}");

        assertThat(reference.raw()).isEqualTo("${ORION_ROOT}");
        VariableReferenceNode variable = assertNode(reference.root(), VariableReferenceNode.class);
        assertThat(variable.name()).isEqualTo("ORION_ROOT");
        assertThat(variable.operator()).isEmpty();
    }

    @Test
    void parsesContextReference() {
        ResourceReference reference = ResourceReference.parse("${bootstrap.baseDir}");

        VariableReferenceNode variable = assertNode(reference.root(), VariableReferenceNode.class);
        assertThat(variable.name()).isEqualTo("bootstrap.baseDir");
        assertThat(variable.isContextReference()).isTrue();
    }

    @Test
    void parsesDefaultAndRequiredOperators() {
        assertOperator("${ORION_ROOT:-orion_root}", ParameterExpansionOperator.DEFAULT_IF_UNSET_OR_BLANK, "orion_root");
        assertOperator("${ORION_ROOT-orion_root}", ParameterExpansionOperator.DEFAULT_IF_UNSET, "orion_root");
        assertOperator("${ORION_ROOT:?required}", ParameterExpansionOperator.ERROR_IF_UNSET_OR_BLANK, "required");
        assertOperator("${ORION_ROOT?required}", ParameterExpansionOperator.ERROR_IF_UNSET, "required");
    }

    @Test
    void parsesCompoundPathWithoutFlatteningSegments() {
        ResourceReference reference = ResourceReference.parse("${ORION_ROOT:-orion_root}/orion.xml");

        InterpolatedReferenceNode interpolation = assertNode(reference.root(), InterpolatedReferenceNode.class);
        assertThat(interpolation.parts()).hasSize(2);
        assertThat(interpolation.parts().get(0)).isInstanceOf(VariableReferenceNode.class);
        assertThat(interpolation.parts().get(1)).isEqualTo(new LiteralReferenceNode("/orion.xml"));
    }

    @Test
    void parsesDocumentReferenceWithNestedSource() {
        ResourceReference reference = ResourceReference.parse("${yaml:${ORION_CONFIG_DATA}/bootstrap/baseDir}");

        DocumentReferenceNode document = assertNode(reference.root(), DocumentReferenceNode.class);
        assertThat(document.format()).isEqualTo(DocumentFormat.YAML);
        assertThat(document.source()).isInstanceOf(VariableReferenceNode.class);
        assertThat(document.path()).isEqualTo(DocumentPath.parse("/bootstrap/baseDir"));
    }

    @Test
    void rejectsInlineDocumentSourceWithoutNestedReference() {
        assertThatThrownBy(() -> ResourceReference.parse("${yaml:bootstrap/baseDir}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document source must be a nested reference");
    }

    private static void assertOperator(
            String raw,
            ParameterExpansionOperator expectedOperator,
            String expectedOperand) {
        VariableReferenceNode variable = assertNode(ResourceReference.parse(raw).root(), VariableReferenceNode.class);
        assertThat(variable.operator()).contains(expectedOperator);
        assertThat(variable.operatorOperand()).contains(expectedOperand);
    }

    private static <T> T assertNode(ReferenceNode node, Class<T> type) {
        assertThat(node).isInstanceOf(type);
        return type.cast(node);
    }
}
```

**Step 2: Run parser test and confirm red**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceParserTest
```

Expected: FAIL at compile time because `ResourceReference` and AST classes do not exist yet.

**Step 3: Commit the red specification tests only if the team wants visible red history**

Usually skip this commit until Task 4 compiles. If committing red tests intentionally:

```bash
git add core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceParserTest.java
git commit -m "test: specify resource reference parser"
```

---

### Task 3: Add Red Resolver Behavior Tests

**Files:**
- Create: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceResolverTest.java`

**Step 1: Write failing resolver tests**

Create tests covering variable lookup, defaults, compound paths, self references, document reads, and cycle detection:

```java
package pro.deta.orion.resource.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.resource.reference.ResourceContent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceReferenceResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesCompoundPathFromEnvironment() {
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_ROOT", tempDir.toString()), Map.of());

        Path resolved = resolver.resolve("$ORION_ROOT/orion.xml", Path.class);

        assertThat(resolved).isEqualTo(tempDir.resolve("orion.xml"));
    }

    @Test
    void appliesUnsetAndBlankDefaultRules() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("BLANK", "   "),
                Map.of());

        assertThat(resolver.resolve("${MISSING:-default}", String.class)).isEqualTo("default");
        assertThat(resolver.resolve("${MISSING-default}", String.class)).isEqualTo("default");
        assertThat(resolver.resolve("${BLANK:-default}", String.class)).isEqualTo("default");
        assertThat(resolver.resolve("${BLANK-default}", String.class)).isEqualTo("   ");
    }

    @Test
    void appliesRequiredRules() {
        ResourceReferenceResolver resolver = standardResolver(Map.of("BLANK", ""), Map.of());

        assertThatThrownBy(() -> resolver.resolve("${MISSING?missing config}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("missing config");
        assertThatThrownBy(() -> resolver.resolve("${BLANK:?blank config}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("blank config");
        assertThat(resolver.resolve("${BLANK?allowed}", String.class)).isEqualTo("");
    }

    @Test
    void resolvesContextSelfReference() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of(),
                Map.of("bootstrap.baseDir", "${ORION_ROOT:-orion_root}"));

        Path resolved = resolver.resolve("${bootstrap.baseDir}/orion.xml", Path.class);

        assertThat(resolved).isEqualTo(Path.of("orion_root").resolve("orion.xml"));
    }

    @Test
    void detectsContextCycles() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of(),
                Map.of("bootstrap.baseDir", "${storage.root}", "storage.root", "${bootstrap.baseDir}"));

        assertThatThrownBy(() -> resolver.resolve("${bootstrap.baseDir}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("bootstrap.baseDir")
                .hasMessageContaining("storage.root");
    }

    @Test
    void readsYamlPathFromInlineContent() {
        ResourceReferenceResolver resolver = standardResolver(
                Map.of("ORION_CONFIG_DATA", "bootstrap:\n  baseDir: /var/lib/orion\n"),
                Map.of());

        String baseDir = resolver.resolve("${yaml:$ORION_CONFIG_DATA/bootstrap/baseDir}", String.class);

        assertThat(baseDir).isEqualTo("/var/lib/orion");
    }

    @Test
    void readsYamlPathFromFilePath() throws Exception {
        Path config = tempDir.resolve("orion.yml");
        Files.writeString(config, "bootstrap:\n  baseDir: /srv/orion\n");
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_CONFIG", config.toString()), Map.of());

        String baseDir = resolver.resolve("${yaml:$ORION_CONFIG/bootstrap/baseDir}", String.class);

        assertThat(baseDir).isEqualTo("/srv/orion");
    }

    @Test
    void failsUnreadablePathInsteadOfParsingItAsInlineYaml() {
        Path missing = tempDir.resolve("missing.yml");
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_CONFIG", missing.toString()), Map.of());

        assertThatThrownBy(() -> resolver.resolve("${yaml:$ORION_CONFIG/bootstrap/baseDir}", String.class))
                .isInstanceOf(ResourceReferenceResolutionException.class)
                .hasMessageContaining("Cannot read document source");
    }

    @Test
    void resolvesFileContentWhenRequested() throws Exception {
        Path config = tempDir.resolve("orion.yml");
        Files.writeString(config, "bootstrap:\n  baseDir: /srv/orion\n");
        ResourceReferenceResolver resolver = standardResolver(Map.of("ORION_CONFIG", config.toString()), Map.of());

        ResourceContent content = resolver.resolve("$ORION_CONFIG", ResourceContent.class);

        assertThat(content.asUtf8String()).contains("baseDir");
    }

    private static ResourceReferenceResolver standardResolver(
            Map<String, String> environment,
            Map<String, String> context) {
        return ResourceReferenceResolver.standard(ResourceReferenceScope.builder()
                .environment(environment)
                .context(context)
                .build());
    }
}
```

**Step 2: Run resolver test and confirm red**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceResolverTest
```

Expected: FAIL at compile time until the public API exists.

---

### Task 4: Add Public API Skeleton And Compile The Red Tests

**Files:**
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReference.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ReferenceNode.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/LiteralReferenceNode.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/VariableReferenceNode.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/InterpolatedReferenceNode.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/AddressReferenceNode.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/DocumentReferenceNode.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ParameterExpansionOperator.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/DocumentFormat.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/DocumentPath.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceScope.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolutionException.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceResolution.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceResolverRegistry.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceCapabilityResolver.java`

**Step 1: Add minimal records and interfaces**

Add the smallest API surface needed for tests to compile. Example skeleton:

```java
package pro.deta.orion.resource.reference;

public record ResourceReference(String raw, ReferenceNode root) {
    public ResourceReference {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Resource reference must not be empty");
        }
        if (root == null) {
            throw new IllegalArgumentException("Resource reference root must not be null");
        }
    }

    public static ResourceReference parse(String raw) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
```

Use package-private AST records unless a test or external call site needs them public. Tests in the same package can still inspect them.

**Step 2: Run tests and confirm behavior is red, not compile-red**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceParserTest,ResourceReferenceResolverTest
```

Expected: tests compile and fail with `UnsupportedOperationException`.

**Step 3: Commit skeleton and red tests**

```bash
git add core/resource-addressing/src/main/java/pro/deta/orion/resource/reference \
        core/resource-addressing/src/test/java/pro/deta/orion/resource/reference
git commit -m "test: specify resource reference model"
```

---

### Task 5: Implement The AST Parser

**Files:**
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceParser.java`
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReference.java`
- Modify: AST files from Task 4
- Test: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceParserTest.java`

**Step 1: Implement recursive parser**

Implement `ResourceReferenceParser` as a small recursive-descent parser:

- Parse plain text as `LiteralReferenceNode`.
- Parse `$NAME` into `VariableReferenceNode`.
- Parse `${NAME}` into `VariableReferenceNode`.
- Parse `${NAME:-word}`, `${NAME-word}`, `${NAME:?message}`, `${NAME?message}` into `VariableReferenceNode` plus `ParameterExpansionOperator`.
- Parse full inputs with multiple parts into `InterpolatedReferenceNode`.
- Parse `${yaml:<source><documentPath>}`, `${toml:<source><documentPath>}`, `${xml:<source><documentPath>}` into `DocumentReferenceNode`.
- For document source boundaries:
  - `$NAME` ends after the simple variable name.
  - `${...}` ends at the matching brace.
  - Anything else fails with "Document source must be a nested reference".

**Step 2: Preserve exact raw text**

`ResourceReference.raw()` must always be the original input. AST nodes may keep their own raw spans if that improves diagnostics.

**Step 3: Run parser tests**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceParserTest
```

Expected: PASS.

**Step 4: Commit parser**

```bash
git add core/resource-addressing/src/main/java/pro/deta/orion/resource/reference \
        core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceParserTest.java
git commit -m "feat: parse resource references"
```

---

### Task 6: Implement Variable Resolution, Defaults, And Cycles

**Files:**
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolver.java`
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceScope.java`
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolutionException.java`
- Test: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceResolverTest.java`

**Step 1: Implement scope builder**

`ResourceReferenceScope` should contain:

- `Map<String, String> environment`
- `Map<String, String> context`
- `int maxDepth`, default `16`

Add:

```java
public static Builder builder()
public Optional<String> variable(String name)
```

Lookup rules:

- Dotted name: context only.
- Simple name: environment first, then context.

**Step 2: Implement scalar resolution**

Implement `ResourceReferenceResolver.resolve(reference, String.class)`:

- Evaluate the AST directly.
- Apply `String.isBlank()` for `:-` and `:?`.
- Parse operator operands as reference fragments.
- Never mutate scope.

**Step 3: Implement cycle detection**

Track variable names being resolved. If a context value references a variable already on the stack, throw `ResourceReferenceResolutionException` with a safe cycle message.

**Step 4: Run resolver subset**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceResolverTest
```

Expected: variable/default/cycle tests pass; content/document tests may still fail.

**Step 5: Commit variable resolution**

```bash
git add core/resource-addressing/src/main/java/pro/deta/orion/resource/reference \
        core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceResolverTest.java
git commit -m "feat: resolve resource reference variables"
```

---

### Task 7: Add Capability Registry And Lightweight Address Resolvers

**Files:**
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceResolverRegistry.java`
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceCapabilityResolver.java`
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/FileAddressCapabilityResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/FileContentCapabilityResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/HttpContentCapabilityResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/S3AddressCapabilityResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/GitAddressCapabilityResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/InlineContentCapabilityResolver.java`
- Test: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceResolverTest.java`
- Test: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceResolverRegistryTest.java`

**Step 1: Define resolver interface**

```java
public interface ResourceCapabilityResolver<T> {
    Class<T> targetType();

    boolean supports(ResourceAddress address, ResourceReferenceScope scope);

    T resolve(ResourceAddress address, ResourceReferenceScope scope);
}
```

If resolving needs nested references, pass a narrow resolver callback rather than the mutable facade.

**Step 2: Implement registry behavior**

`ResourceResolverRegistry` must:

- register resolvers explicitly;
- reject ambiguous matches;
- fail with a message naming target type and safe address;
- allow additional resolvers for custom schemes such as `local:`.

**Step 3: Implement built-ins**

Built-ins for this pass:

- `file:` and empty scheme to `Path`.
- `file:` and empty scheme to `ResourceContent`.
- `content:` to `ResourceContent`.
- `http:` and `https:` to `ResourceContent` using Java `HttpClient`.
- `s3:` to `S3ObjectLocation`; content still uses the existing lightweight `S3ObjectContentReader` interface.
- `git:`, `git+ssh:`, `git+http:`, `git+https:` to `GitRepositoryLocation`; content still uses the existing lightweight `GitRepositoryContentReader` interface.

Do not add AWS SDK or JGit as dependencies in this module for the lightweight S3/Git content path.

**Step 4: Add registry extension test**

Add `ResourceResolverRegistryTest` proving `local:` can be supplied externally:

```java
@Test
void customResolverCanOwnLocalScheme() {
    ResourceReferenceResolver resolver = ResourceReferenceResolver.builder()
            .scope(ResourceReferenceScope.empty())
            .registry(ResourceResolverRegistry.builder()
                    .withDefaults()
                    .add(new LocalRepositoryResolver())
                    .build())
            .build();

    LocalRepository repository = resolver.resolve("local:orion", LocalRepository.class);

    assertThat(repository.name()).isEqualTo("orion");
}
```

**Step 5: Run registry and resolver tests**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceResolverTest,ResourceResolverRegistryTest
```

Expected: PASS except document dereferencing if not implemented yet.

**Step 6: Commit capability registry**

```bash
git add core/resource-addressing/src/main/java/pro/deta/orion/resource/reference \
        core/resource-addressing/src/test/java/pro/deta/orion/resource/reference
git commit -m "feat: add resource capability resolver registry"
```

---

### Task 8: Implement YAML, TOML, And XML Document Dereferencing

**Files:**
- Modify: `core/resource-addressing/pom.xml`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/DocumentReferenceResolver.java`
- Create: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/DocumentSourceResolver.java`
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference/ResourceReferenceResolver.java`
- Test: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceResolverTest.java`

**Step 1: Add parser dependencies**

Add dependencies:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-xml</artifactId>
</dependency>
<dependency>
    <groupId>com.moandjiezana.toml</groupId>
    <artifactId>toml4j</artifactId>
    <version>0.7.2</version>
</dependency>
```

**Step 2: Resolve document source**

`DocumentSourceResolver` should:

- resolve source AST to a value;
- parse direct `ResourceContent`;
- read registered addresses or paths as `ResourceContent`;
- parse obvious inline content;
- fail for unreadable path/URI-like scalars.

**Step 3: Parse documents**

Use:

- Jackson YAML into `JsonNode`;
- Jackson XML into `JsonNode`;
- toml4j into `Map<String, Object>`, then convert to `JsonNode` or traverse maps directly.

`DocumentPath` should use slash-separated segments for this pass. Do not implement filters, wildcards, attributes, or JSON Pointer escaping unless a test requires it.

**Step 4: Run document tests**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing -Dtest=ResourceReferenceResolverTest
```

Expected: PASS.

**Step 5: Commit document dereferencing**

```bash
git add core/resource-addressing/pom.xml \
        core/resource-addressing/src/main/java/pro/deta/orion/resource/reference \
        core/resource-addressing/src/test/java/pro/deta/orion/resource/reference/ResourceReferenceResolverTest.java
git commit -m "feat: resolve document resource references"
```

---

### Task 9: Add Module README With Syntax Matrix

**Files:**
- Create: `core/resource-addressing/README.md`
- Modify: `docs/plans/2026-05-14-resource-reference-address-model.md`

**Step 1: Document accepted syntax**

`core/resource-addressing/README.md` must include:

- variable references;
- default and required operators;
- compound path interpolation;
- document references;
- source rules for YAML/TOML/XML;
- capability examples: `String`, `Path`, `ResourceContent`, `ExternalDirectory`, Git/S3 address types;
- extension resolver example for `local:`.

**Step 2: Mark old colon-only syntax as legacy**

Update the design doc to point to the README as the current syntax spec. Keep historical discussion but avoid presenting `file:env:ORION_CONFIG` as the preferred form.

**Step 3: Commit docs**

```bash
git add core/resource-addressing/README.md \
        docs/plans/2026-05-14-resource-reference-address-model.md
git commit -m "docs: document resource reference syntax"
```

---

### Task 10: Compatibility Cleanup Inside Resource Addressing

**Files:**
- Modify: `core/resource-addressing/src/main/java/pro/deta/orion/resource/reference`
- Modify: `core/resource-addressing/src/test/java/pro/deta/orion/resource/reference`

**Step 1: Keep the public boundary narrow**

New code should use `ResourceReferenceResolver` directly and register optional
capabilities through `ResourceResolverRegistry`.

**Step 2: Rename only when safe**

Do not mechanically rename public classes until the new tests are green. If a
rename causes broad churn, leave a small compatibility wrapper:

```java
@Deprecated(forRemoval = true)
public final class ResourceResolver {
    private final ResourceReferenceResolver delegate;
}
```

**Step 3: Run all module tests**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing
```

Expected: PASS.

**Step 4: Commit cleanup**

```bash
git add core/resource-addressing
git commit -m "refactor: migrate resource addressing to references"
```

---

### Task 11: Final Verification

**Files:**
- No new files unless failures require fixes.

**Step 1: Run module tests**

Run:

```bash
mvn test -Pdev -pl core/resource-addressing
```

Expected: PASS.

**Step 2: Run full routine tests**

Run:

```bash
mvn test -Pdev
```

Expected: PASS.

**Step 3: Inspect status**

Run:

```bash
git status --short
git log --oneline -5
```

Expected: working tree clean after commits, with the implementation commits visible.

## Non-Goals For This Plan

- Do not replace `LocationConfigurationProvider` or server bootstrap startup in this plan.
- Do not add heavy AWS SDK or JGit runtime clients to `core/resource-addressing`.
- Do not implement document wildcards, filters, XML attributes, or advanced query languages.
- Do not implement assignment operators such as `${NAME:=word}`.
- Do not log resolved secret values.
