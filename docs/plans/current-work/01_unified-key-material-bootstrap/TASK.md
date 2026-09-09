# Unify Key Material Bootstrap and Short-Lived JWTs

Status: todo
Related plan: ../../2026-05-14-key-material-keystore-and-ca.md

Make one protected material store the bootstrap root of trust for Orion-owned
keys. Open it concurrently with the `orion.xml` snapshot from local native Git,
then gate configuration activation on both inputs so encrypted configuration
secrets can be resolved safely.

Keep long-lived cryptographic material in the protected store and encrypted
secret payloads in `orion.xml`. Short-lived JWTs are renewable bearer tokens,
not one-time tokens. Do not expose the raw material service broadly through
dependency injection.
