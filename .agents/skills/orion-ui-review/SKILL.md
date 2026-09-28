---
name: orion-ui-review
description: >-
  Use when reviewing Orion frontend layout, responsiveness, or browser interactions,
  including requests to find and fix reproducible UI bugs.
---

# Orion UI Review

Review the running Vue UI against observable behavior and the user's references.
When repairs are requested, carry confirmed bugs through minimal fixes and browser
verification in the same task.

## Scope and routing

Use `orion-minimal-implementation`, repository `AGENTS.md`, and
[workflow and verification definitions](../../../docs/definitions.md).
Review-only requests remain read-only. Explicit repair requests authorize fixes
within their scope; do not ask again merely because the task is called a review.
Select Quick, Simple, or Change from the actual result and honor its checkpoints
and commit rules. This skill adds no separate approval gate.

Default scope is `net/frontend/ui`: layout, responsive behavior, navigation,
dialogs, and visible error/loading states. Preserve the existing visual language
and product behavior. Treat preferences about colors or composition as design
choices requiring a user reference, rather than inventing a redesign.

Use existing module-report findings as hypotheses. If a repair resolves one, use
`orion-review` to update it in the repair checkpoint. Create audit reports only
when requested. Setting up this process does not itself start UI repairs.

## Running UI and observing the browser

- Start with `make help` and reuse existing processes. `make run-frontend` calls
  `npm run dev` in `net/frontend/ui`; Vite normally serves `http://localhost:4173`
  with hot updates and proxies `/api` to Orion on port `8000`.
- `make run-server` serves the backend and packaged UI. Review source changes on
  the Vite URL; use the startup output if its port differs.
- Use available browser tools or the existing Node/Playwright installation in
  `tests/integration-test/playwright`. The external-services fixture exposes
  Chromium CDP at `http://localhost:9222` and a visible browser at
  `http://localhost:6080/vnc.html?autoconnect=1`.
- Check reachability from that browser: container `localhost` differs from host
  `localhost`, and a Vite listener bound to host loopback may be unreachable from
  the container. Follow the fixture's existing host mapping and browser setup in
  [its README](../../../tests/external-services/README.md). Do not assume the
  packaged page is the current development UI.
- Use a dedicated page/context; preserve other sessions' pages and processes.
  Share the observer URL and current scenario. Save screenshots and useful traces
  under a session-specific `target/ui-review/` directory.

For browser scenarios observed through noVNC, make actions easy to follow:

- Bring the dedicated review page to the foreground and confirm it is visible
  through the observer URL before starting the scenario.
- Pause for about one second between navigation, clicks, and field edits. Keep
  normal locator assertions and readiness checks; these pauses are presentation
  timing, not a substitute for waiting for the UI to become ready.
- Before each action, show a short action label on the dedicated review page.
  Mark the actual click position with a visible ring or dot for about one second.
  Use a temporary overlay with `pointer-events: none`, installed by the browser
  scenario (for example with `context.addInitScript` and a `pointerdown` listener).
  Install it only in an owned page or a new dedicated context. Do not add
  instrumentation to production UI or modify another session's page. When a click
  navigates, briefly highlight its target beforehand and restore the action label
  on the destination page.
- Describe controls, never entered tokens, passwords, or other secret values.
  Keep markers visible in monitoring captures; clear both labels and click markers
  before taking clean before/after layout screenshots. Use synthetic credentials
  for recorded scenarios or exclude secret-bearing traces and logs.
- Apply this pacing to observed review/reproduction scenarios. Ordinary unattended
  regression tests retain their normal timing.

Without backend credentials, continue reachable disconnected and layout scenarios;
report authentication-dependent coverage as unavailable. Use explicit test data or
mocked responses only where needed, label them, and keep real backend integration
claims separate. If browser access is unavailable, report source findings and
the limitation; passing unit tests does not establish browser layout correctness.

## Review and repair loop

Choose scenarios from the requested screens. Include desktop and mobile widths
(for example 1440, 768, and 390 pixels), plus a short viewport such as 390×400:
sidebar actions and dialog controls must remain reachable by scrolling. Check
dialogs above navigation, menu open/close, light/dark themes, long repository
names and URLs, and relevant empty, loading, and error states.

For each candidate:

1. Record the URL, viewport, state/data, exact actions, expected/actual behavior,
   screenshot, and relevant console or failed-request evidence. Distinguish
   browser-reproduced bugs from source hypotheses and unavailable dependencies.
2. Trace the cause through the existing component and styles. When repairs are
   authorized, apply the smallest fix and meaningful regression coverage. Reuse
   existing Vue, CSS, Vitest, and Playwright mechanisms.
3. Repeat the exact scenario and adjacent viewport/state, including the short
   viewport when navigation or dialogs changed. Compare before/after screenshots
   with the same browser, data, theme, and dimensions.
4. Run affected frontend checks (`npm test`, `npm run build`) and every required
   pre-commit check from the repository table. Frontend source is Maven-managed:
   standalone npm checks do not replace `make test`. Apply the repository's
   automatic review and staging rules before the workflow's commit.

Finish when the scoped scenarios and authorized repairs are verified, or report
the specific access or product decision preventing further work. Return fixed
bugs, reproduction and screenshot links, actual checks, remaining hypotheses,
and coverage limits. Keep findings bounded to the requested UI.

Example invocation:

```text
$orion-ui-review Review sign-in and mobile navigation at localhost:4173,
fix reproducible bugs, and show before/after screenshots.
```
