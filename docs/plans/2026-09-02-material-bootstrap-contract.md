# Material Bootstrap Contract

## Goal

Define the boundary between process bootstrap configuration, the protected key
material store, the versioned `orion.xml` desired-state snapshot, and derived
runtime state. Material and configuration load independently and meet at one
activation barrier. Nothing derived from a candidate snapshot becomes visible
before the complete pair has been decrypted and validated.

This contract defines orchestration and recovery semantics. Loading
`orion.xml` from local native Git, typed material capabilities, secret envelope
cryptography, and production snapshot publication remain in their dedicated
follow-up tasks.

## Ownership

| Owner | Contains | Must not contain |
| --- | --- | --- |
| Process bootstrap configuration | Material-store type and location, password or credential reference, local configuration-repository location and ref, node identity, bootstrap trust and recovery policy | Secrets needed to open its own references, application desired state, private keys |
| Protected material store | Long-lived private and symmetric keys, certificate chains, trusted certificates, entry purpose, algorithm, version, and cluster or node scope | Desired-state configuration, encrypted application payloads, its own password |
| Versioned `orion.xml` | Desired state, public identifiers and keys, purpose-scoped material references, encrypted secret envelopes | Material-store location or password, configuration-repository locator, raw private or symmetric keys, mutable runtime observations |
| Runtime state | One immutable active projection, its material revision and configuration commit, transient decrypted values, queues, leases, status, and observations | Authoritative keys or configuration, recoverable plaintext secrets |

The values required to locate, authenticate, parse, and select either bootstrap
input cannot live only inside `orion.xml`: the material-store location and
credential reference, the configuration repository location and selected ref,
the node identity, and any trust roots required to open those sources must be
available before the document can be read or decrypted.

Material entries have an explicit scope:

- cluster-wide entries are shared Orion identity, JWT signing and verification,
  configuration wrapping keys, shared service identity, and CA issuer material;
- node-local entries are SSH host identity and other credentials that identify
  one machine rather than the cluster;
- a configuration reference states its purpose and scope. A node cannot satisfy
  a cluster-wide reference with node-local material or use another node's
  identity.

The initial local PKCS12 implementation may hold both scopes in one protected
store, but scope remains entry metadata and is validated before activation.

## Input and Activation Contract

Each loader returns either a typed failure or an immutable value with a durable
revision:

- the material revision is the content-store version of the complete protected
  store;
- the configuration revision is the native Git commit containing `orion.xml`.

The coordinator starts both loaders before observing either result. A
configuration loader may fetch, parse, and structurally validate the document
without material. It must not decrypt envelopes, resolve material references,
or publish subsystem projections. Those operations belong to candidate
preparation after both inputs have crossed the barrier.

```text
material loader ------ loaded material revision ----+
                                                    |
                                                    +--> prepare complete pair
                                                    |      - decrypt secrets
configuration loader -- loaded configuration ------+      - validate references
                                                           - build projections
                                                                    |
                                                                    v
                                                          atomic publication
```

Candidate preparation validates at least:

- supported store and document versions;
- cluster identity and node placement;
- referenced alias existence, purpose, scope, algorithm, and version;
- secret-envelope authentication and context binding;
- the complete configuration and every derived subsystem projection.

Publication receives one candidate containing both input revisions and the
complete immutable runtime projection. Publication is atomic: success replaces
the previous projection as one unit; failure leaves the previous projection
unchanged. A cold start has no fallback projection and therefore remains
inactive on any failure.

## Failure States

Failures have a source (`MATERIAL_STORE`, `CONFIGURATION_SNAPSHOT`,
`INPUT_PAIR`, or `RUNTIME`), a stage (`LOAD`, `PREPARE`, or `PUBLISH`), and one
of these codes:

| Code | Meaning | Examples |
| --- | --- | --- |
| `MISSING` | A required source or item does not exist | No store, no configured ref, missing `orion.xml`, missing alias |
| `UNAVAILABLE` | The source exists but cannot currently be opened | I/O failure, denied access, unavailable credential, timeout |
| `CORRUPT` | Bytes or authenticated content are invalid | Malformed PKCS12, malformed XML, failed envelope authentication |
| `INCOMPATIBLE` | Content is valid but unsupported by this runtime | Unknown schema, store format, envelope version, or algorithm |
| `MISMATCHED` | Both inputs are individually valid but cannot form one candidate | Wrong cluster, purpose, scope, alias version, or key algorithm |
| `ACTIVATION_FAILED` | A prepared candidate could not be installed atomically | A subsystem rejects its projection before the publication swap |
| `INTERNAL` | An implementation violated the bootstrap API contract | Null result or unexpected exception from a bootstrap stage |

Wrong or unavailable store credentials are `UNAVAILABLE`, not proof that the
store is corrupt. Diagnostics expose source, stage, code, and safe revision
identifiers, but never credential values, key bytes, encrypted payloads, or
decrypted secrets. When both independent loads fail, the result reports both
failures in deterministic material-then-configuration order.

## Recovery Rules

1. A failed cold start publishes nothing and externally visible services remain
   stopped.
2. A failed reload keeps the previously active candidate. A partially prepared
   candidate is never retained or exposed.
3. Missing or corrupt existing material is never replaced through
   `createIfMissing`; recovery requires restoring an operator-selected backup
   or correcting the source.
4. A configuration rollback selects an explicit Git commit. It is activated
   only after validation against the currently selected material revision.
5. Material rotation is staged: add the new entry, publish configuration that
   references it, confirm activation, then retire old material after its
   retention window. No cross-store atomic commit is assumed.
6. Successful publication records the accepted material revision and
   configuration commit as non-secret recovery metadata. The record is updated
   after publication, never before it.
7. Restart normally evaluates the configured desired revisions again. An
   optional last-accepted recovery mode may select the recorded pair, but must
   load both exact sources again, repeat full preparation, and report degraded
   recovery explicitly. It never activates cached plaintext runtime state.
8. If an exact material revision is no longer available, recovery requires a
   coordinated material backup restore. A configuration commit alone is not a
   complete backup.
9. `INCOMPATIBLE` and `MISMATCHED` inputs are not repaired automatically.
   Operators migrate the input, select a compatible commit, or restore a
   coherent pair, then retry the unchanged barrier.

Production storage tasks own durable material backup and publication. The
native Git configuration task owns commit selection and accepted-revision
storage. The coordinator contract deliberately accepts loaders for already
selected revisions so those policies remain outside the barrier itself.

## Acceptance Scenarios

- Material completes first and no preparation or publication occurs until the
  configuration result arrives.
- Configuration completes first and cannot decrypt or publish before material
  arrives.
- Either or both loaders fail without invoking preparation or publication.
- A mismatched pair and a publication failure leave the prior active candidate
  unchanged.
- A fresh coordinator can reconstruct and activate the same durable pair after
  restart; it does not depend on a cached runtime projection.
