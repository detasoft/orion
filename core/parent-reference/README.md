# Resource Reference

`core/resource-reference` groups the resource reference modules:

- `reference-api` contains the AST-based resource reference model in
  `pro.deta.orion.resource.reference`;
- `antlr-parser` builds the ANTLR parser classes used by `reference-api`;
- `reference-resolvers` contains optional Git and S3 capability resolvers.

The model parses user/config input into an immutable AST and resolves it to
caller-requested capabilities such as `String`, `Path`, `ResourceContent`, or a
type supplied by an extension resolver.

Concrete storage or transport capabilities are intentionally outside the
`reference-api` module. The `reference-resolvers` module supplies optional Git
and S3 capability resolvers on top of this AST and registry API.

## Parser Source

The grammar source is:

```text
antlr-parser/src/main/resources/pro/deta/orion/resource/reference/ResourceReference.g4
```

Maven generates ANTLR classes under:

```text
antlr-parser/target/generated-sources/resource-reference
```

Generated classes are internal to `ResourceReferenceParser` and are not part of
the public API.

## Variables

Supported forms:

```text
$ORION_ROOT
${ORION_ROOT}
${bootstrap.baseDir}
```

`$NAME` is shorthand for simple names only: `[A-Za-z_][A-Za-z0-9_]*`.
Use `${...}` for dotted context names, defaults, required references, and nested
syntax.

Lookup rules:

- dotted names read the context map;
- simple names read environment first, then context;
- context values may themselves contain references;
- direct and indirect context cycles fail.

## Defaults And Required Values

Supported Bash-compatible operators:

```text
${ORION_ROOT:-orion_root}
${ORION_ROOT-orion_root}
${ORION_ROOT:?ORION_ROOT is required}
${ORION_ROOT?ORION_ROOT is required}
```

Meaning:

- `:-` uses the fallback when the value is unset or blank;
- `-` uses the fallback only when the value is unset;
- `:?` fails when the value is unset or blank;
- `?` fails only when the value is unset.

Blank means `String.isBlank()` after conversion to string. Assignment operators
such as `:=` and `=` are not supported.

## Compound References

References may be combined with literal path fragments:

```text
$ORION_ROOT/orion.xml
$ORION_ROOT/$PATH_TO_CONFIG
${ORION_ROOT:-orion_root}/orion.xml
${bootstrap.baseDir}/orion.xml
```

The parser keeps these as `InterpolatedReferenceNode` parts. It does not flatten
the expression before resolving nested nodes.

## Addresses

Explicit scheme-bearing references are address nodes:

```text
file:${ORION_CONFIG:-config.yml}
s3://${CONFIG_BUCKET}/orion.yml
git+https://${GIT_HOST}/team/config.git
content:${ORION_CONFIG_DATA}
```

Current built-in capability behavior:

- resolving to `String` returns the resolved scalar/address text;
- resolving to `Path` treats plain or `file:` values as local paths;
- resolving to `ResourceContent` reads local files and `http:`/`https:` URLs,
  otherwise obvious inline content is returned as bytes.

`s3:` and Git schemes are part of the address syntax and registry design. Their
location and content readers should stay outside this module; plug them in
through `ResourceCapabilityResolver`.

## Document References

Document references read a property from YAML, TOML, or XML:

```text
${yaml:${ORION_CONFIG_DATA}/bootstrap/baseDir}
${yaml:$ORION_CONFIG_DATA/bootstrap/baseDir}
${yaml:${ORION_CONFIG_PATH:-config.yml}/bootstrap/baseDir}
${toml:${ORION_CONFIG_PATH:-config.toml}/bootstrap/baseDir}
${xml:${ORION_ACL_PATH:-orion.xml}/accessControl/users/root}
```

The source must be nested `${...}` or `$NAME`. Plain inline source text is
rejected:

```text
${yaml:bootstrap/baseDir}
```

If an explicit source scheme is needed, wrap it so the source/path boundary is
unambiguous:

```text
${yaml:${file:${ORION_CONFIG_PATH:-config.yml}}/bootstrap/baseDir}
```

Document source handling:

- `ResourceContent` or obvious inline content is parsed directly;
- a readable local path is read and parsed;
- a value that looks like a path or URI but cannot be read fails;
- no wildcard, filter, or query language is implemented.

Document paths are slash-separated segments such as `/bootstrap/baseDir`.
YAML is parsed directly with SnakeYAML, TOML with `toml4j`, and XML with the
JDK XML parser. These document references are lookup helpers, not data-binding
APIs.

## Extension Resolvers

Modules can add capabilities without changing the parser:

```java
ResourceReferenceResolver resolver = ResourceReferenceResolver.builder()
        .scope(ResourceReferenceScope.empty())
        .registry(ResourceResolverRegistry.builder()
                .withDefaults()
                .add(new LocalRepositoryResolver())
                .build())
        .build();

LocalRepository repository = resolver.resolve("local:orion", LocalRepository.class);
```

The registry fails on ambiguous matches. For example, an Orion runtime module can
own `local:` and resolve it to an internal local Git repository, while this core
module can treat `local:` as unknown unless that resolver is registered.
