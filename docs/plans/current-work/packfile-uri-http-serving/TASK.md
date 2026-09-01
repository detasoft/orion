# Serve Packfile URI Packs Over HTTP

Status: done
Source: follow-up from protocol v2 packfile URI response support.

Add configuration for a public packfile base URI and a dedicated HTTP endpoint
that serves generated packfiles by request. The fetch server should emit
packfile URI responses only when the base URI and pack availability are
configured, and the endpoint should validate requested pack identifiers before
streaming pack bytes.

Support an `auto` base URI mode for Smart HTTP fetches by deriving the packfile
URI origin and repository path from the current HTTP request. Use TLS state,
`Host`, and only trusted `Forwarded` or `X-Forwarded-*` headers when Orion is
behind a configured trusted proxy. For SSH and other non-HTTP fetch contexts,
require an explicit configured public base URI or leave packfile URI responses
disabled.

Prefer same-origin packfile URLs when possible so Git clients can reuse the
credentials already used for the fetch request.
