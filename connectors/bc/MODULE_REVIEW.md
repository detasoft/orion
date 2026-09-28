# Module Review: `bc`

## 1. The shared crypto connector exports an unused TLS provider

**Problem.** `bctls-debug-jdk18on` reaches every consumer and the shaded server although no repository
consumer selects BCJSSE or uses Bouncy Castle TLS APIs. The other three crypto artifacts have real consumers.

**Sources.** [Dependency](pom.xml#L86),
[TLS context](../../core/key-material/src/main/java/pro/deta/orion/keymaterial/KeyMaterialCapabilities.java#L518),
[DTLS context](../../core/communication/src/main/java/pro/deta/orion/comm/common/OrionDTLSParameters.java#L36), and
[server packaging](../../core/bootstrap/pom.xml#L108). Source, hidden configuration and documentation searches
found no BCJSSE registration or selection. Installed MINA sources register the crypto `BouncyCastleProvider`
from `bcprov`, while Netty only wraps BC engines when a caller supplies one.

**Documented behavior and contract.** No BCJSSE deployment requirement was found. Provider, PKIX and utility
classes are needed by SSH, certificates and key material. TLS/DTLS currently use the default JSSE provider;
the connector's coherent version and exclusions prevent normal/debug provider duplication.

**Minimal repair.** Delete the `bctls-debug-jdk18on` dependency only. Preserve the three required artifacts,
their version pin and exclusions. Verify the real reactor through `make test`; do not add POM-text tests.

**Alternatives and consequences.** Moving it to communication still exports an unused provider. Keeping it
adds unused runtime/package surface. Deletion changes the dependency graph without changing configured
TLS/DTLS behavior; external JVM provider injection is not an established repository-supported requirement.

**Confidence.** High for repository use and supported configuration; externally injected providers are
unknown. The audit did not run builds or handshakes.

**Priority signals.** Importance: low, unused dependency. Repair ease: very high, one dependency deletion.
