# Git wire configuration design

## Goal

Introduce a small, code-only configuration object for Git wire protocol
features. The configuration is supplied when a `GitMinimalWireMachine` is
created. It is not loaded from YAML or wired through bootstrap configuration.

The same configuration controls both advertised capabilities and whether the
corresponding input is accepted.

## Configuration model

Add an immutable `GitWireConfiguration` composed of three version- and
service-specific records:

```java
public record GitWireConfiguration(
        LegacyUploadPack uploadPack,
        LegacyReceivePack receivePack,
        ProtocolV2 protocolV2) {

    public record LegacyUploadPack(
            boolean multiAckDetailed,
            boolean thinPack,
            boolean sideBand64k,
            boolean ofsDelta,
            boolean symref,
            boolean agent) {}

    public record LegacyReceivePack(
            boolean reportStatus,
            boolean sideBand64k,
            boolean ofsDelta,
            boolean objectFormat,
            boolean agent) {}

    public record ProtocolV2(
            boolean lsRefs,
            boolean lsRefsUnborn,
            boolean fetch,
            boolean serverOption) {}
}
```

`allSupported()` returns the compatibility default used by existing
constructors and tests. Nested values and the top-level value are required to
be non-null. `lsRefsUnborn` requires `lsRefs`.

## Propagation

`GitMinimalWireMachine` accepts the configuration in a new constructor and
stores it in `Context`. Existing constructors delegate with
`GitWireConfiguration.allSupported()`. Test context factories gain an overload
for explicit configuration while retaining their existing defaults.

The repository service uses the legacy portions to construct upload-pack and
receive-pack advertisements. Protocol v2 advertisement serialization receives
the v2 portion directly. Continuations consult the same context value when
dispatching commands or negotiating optional behavior.

## Legacy protocol behavior

Legacy upload-pack advertisement is assembled in its existing deterministic
order from enabled features:

- `multi_ack_detailed`
- `thin-pack`
- `side-band-64k`
- `ofs-delta`
- `agent=orion-native`
- dynamic `symref=HEAD:<target>`

Legacy receive-pack advertisement similarly selects:

- `report-status`
- `side-band-64k`
- `ofs-delta`
- `object-format=sha1`
- `agent=orion-native`

A client cannot activate a disabled advertised capability. Existing
negotiation already intersects client requests with the server advertisement;
receive-pack processing must follow the same rule where it consumes
capabilities.

## Protocol v2 behavior

The v2 advertisement always begins with `version 2`. It then emits enabled
commands and features in stable order:

- `ls-refs`, with `=unborn` only when `lsRefsUnborn` is enabled
- `fetch`
- `server-option`

Disabled `ls-refs` and `fetch` commands are rejected as invalid protocol v2
requests. The `unborn` argument is accepted only when both `lsRefs` and
`lsRefsUnborn` are enabled.

The command-header parser accepts `server-option=<value>` packets before the
delimiter. When `serverOption` is disabled, such a packet is an invalid v2
request. When enabled, it is advertised and the server starts normally, but
actual processing throws:

```java
new IllegalStateException("not implemented")
```

This preserves the requested placeholder behavior while making the incomplete
feature explicit only when exercised.

## Error handling

Invalid feature combinations are rejected when the configuration object is
constructed. Client use of disabled commands or arguments follows the existing
protocol-error continuation flow. The explicitly enabled but unimplemented
`server-option` path throws `IllegalStateException("not implemented")`.

Output serialization and delivery failures continue to use `SendResult`; the
configuration change does not introduce exception-based expected output flow.

## Tests

Tests cover:

- compatibility defaults and invalid configuration combinations;
- enabled and disabled legacy advertisement fields;
- enabled and disabled v2 command advertisement and dispatch;
- `ls-refs=unborn` advertisement and argument gating;
- disabled `server-option` rejection;
- enabled `server-option` advertisement and the processing-time
  `IllegalStateException`;
- existing constructors retaining all-supported behavior.

