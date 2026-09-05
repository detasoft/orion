# Java/Kotlin Backend Review Guide

Use this reference only for Java, Kotlin, Spring, or other JVM backend code. It extends the main workflow; it does not turn the review into a language-style audit.

## Build the JVM-Specific Map

Inspect the actual module graph and runtime composition before judging package boundaries:

- Gradle or Maven modules and dependency direction;
- application, plugin, and service entry points;
- Spring configuration, component scanning, conditional beans, and profiles;
- persistence mappings, migrations, transactions, caches, and outbox or journal mechanisms;
- async executors, coroutines, reactive pipelines, scheduled jobs, locks, and thread ownership;
- serialization formats and externally persisted type names;
- Java/Kotlin interoperability and public binary compatibility where plugins or third parties are involved.

Package names alone are weak evidence of an architectural boundary. Tests, dependency declarations, runtime wiring, and serialization often reveal the real boundary.

## High-Value Smells

### Interfaces and indirection

Look for interfaces with one implementation, `Impl` pairs, provider or factory chains, and Spring beans whose only function is forwarding. A single implementation is not itself a defect: retain the interface when it protects a real module or API boundary, test seam, plugin contract, alternate runtime binding, or compatibility promise. Otherwise test whether direct ownership is smaller.

Be suspicious of abstractions introduced only to make mocking easy. Prefer testing through stable behavior or using a small real collaborator when that removes production indirection.

### One-consumer classes and visibility

When a class is used by only one owning class or subsystem, prefer recommending that it become non-public and stay close to that owner: for example, a private nested class, a package-private Java type, or a private or `internal` Kotlin declaration, using the narrowest visibility compatible with the real boundary. Public visibility creates an apparent contract and makes later simplification harder.

Do not stop at recommending reduced visibility. Ask whether the one-consumer class represents an independently useful concept or merely fragments the owner's behavior. Recommend inlining, merging, or deleting it when that produces a smaller and clearer model. Do not propose extracting a class merely to satisfy a pattern, obtain a mock seam, or shorten a file.

Before narrowing or merging, verify Spring construction and proxying, reflection, serialization, persistence mapping, service loading, tests that instantiate the type, plugin implementations, and source or binary compatibility. These are possible reasons to retain visibility or separation, not assumptions that every class needs them.

### DTO and mapper chains

Trace semantically identical data through controller DTOs, service models, events, persistence entities, and client models. Determine which boundaries truly require representation independence. Merge representations only when validation, versioning, security filtering, persistence evolution, and ownership do not require separation.

Generated code and external wire contracts are evidence against casual merging.

### Spring-induced architecture

Identify behavior fragmented across annotations, aspects, interceptors, events, transactions, proxies, and conditional configuration. Hidden ordering and proxy semantics can create more complexity than an explicit call.

Check for:

- self-invocation that bypasses proxies;
- transactional boundaries split across services for framework reasons;
- domain events used as synchronous function calls in disguise;
- bean indirection used only to break dependency cycles;
- configuration classes duplicating a domain hierarchy;
- optional injection or profiles creating untested architectural variants.

Do not recommend removing framework boundaries that provide verified transactionality, security, compatibility, or operational isolation.

### Kotlin and Java duplication

Look for parallel Java and Kotlin APIs, builders around data classes, nullable wrappers around `Optional`, duplicate extension and utility functions, SAM or listener adapters, and overload families created for language interoperability.

Before deleting them, verify source and binary compatibility requirements for Java callers, Kotlin default arguments, plugins, reflection, serialization, and published APIs.

### State, concurrency, and lifecycle

Map ownership of executors, coroutine scopes, threads, locks, subscriptions, resources, and shutdown hooks. Find the same lifecycle represented in bean state, database fields, futures, queues, and listeners.

Challenge:

- hand-written state machines coordinating components;
- callbacks or listeners that could become a durable log plus query;
- `CompletableFuture` or coroutine layers that add scheduling without useful concurrency;
- synchronization that protects cached knowledge instead of authoritative state;
- exactly-once behavior simulated across non-transactional boundaries;
- recovery logic that requires reconstructing ephemeral coordination.

Do not simplify concurrency until the required ordering, atomicity, cancellation, backpressure, restart, and shutdown semantics are explicit.

### Exceptions and result models

Look for the same failure repeatedly wrapped, translated, or split among exceptions, sealed results, nullable returns, and status enums. Prefer one failure vocabulary per real boundary. Preserve translations that prevent domain or infrastructure details from leaking across a public contract.

### Plugin and compatibility boundaries

Treat extension points differently from internal abstractions. Verify whether implementations live outside the repository, are loaded reflectively, or rely on binary or serialization compatibility. A locally single-implementation interface may still be a real public contract.

When simplification requires breaking compatibility, propose an adapter and deprecation migration with an explicit removal point rather than leaving two permanent architectures.

## Evidence Techniques

Use repository-wide symbol and text search to find implementations, wiring, callers, serialized names, reflection, service loaders, and tests. Inspect dependency reports or build files where transitive direction matters. Use history only to understand design pressure, never as proof that the current design remains necessary.

When proposing deletion, identify a concrete verification method such as compilation of dependent modules, plugin compatibility checks, focused concurrency tests, migration tests, or restart and recovery tests. Avoid asserting that Spring wiring or reflection will be safe based solely on static references.
