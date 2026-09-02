# Cloudflare Integration Test Dist Profile Design

## Goal

Prevent routine Maven verification from changing Cloudflare DNS state. Run
`IntegrationCloudflareIT` only when the `dist` Maven profile is active and a
Cloudflare API token is available.

## Design

Guard `IntegrationCloudflareIT` with JUnit's `@EnabledIfSystemProperty`. The
integration-test module defines the guard property as `false`, passes it to the
Failsafe fork, and changes it to `true` from a module-local `dist` profile.
The existing token assumption remains as a second safety gate.

This keeps the restriction next to the test module and avoids fragile Failsafe
include/exclude merging. Other integration tests remain unchanged.

## Verification

Add a small configuration test that checks the JUnit guard and both Maven
property values. Run the guarded test through Failsafe without `dist` to prove
that it is skipped without contacting Cloudflare. Do not execute the guarded
test with `dist` during routine verification because that profile explicitly
authorizes external state mutation when credentials are present.
