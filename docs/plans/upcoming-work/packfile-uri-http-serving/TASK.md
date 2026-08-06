# Serve Packfile URI Packs Over HTTP

Status: todo
Source: follow-up from protocol v2 packfile URI response support.

Add configuration for a public packfile base URI and a dedicated HTTP endpoint
that serves generated packfiles by request. The fetch server should emit
packfile URI responses only when the base URI and pack availability are
configured, and the endpoint should validate requested pack identifiers before
streaming pack bytes.
