# Interactive Terminal Corrections and Administration

Task: `current-work/08_interactive-ssh-shell/01_terminal-administration.md`.

## Current model and minimal delta

`InteractiveTerminal` owns one active operation, its cancellation and prompt lifecycle. `CommandNavigator`
resolves the authorized command graph. `TerminalDisplay` owns serialized output. Reuse these owners for
the three correctness findings in `core/command/MODULE_REVIEW.md`; do not add a second operation runner.

`OrionDocument` is the immutable versioned configuration. `OrionAccessControlServiceImpl` already loads,
validates, saves and activates it through `AccessControlStorage`, publishing `OrionDesiredState`. Reuse
this persistence and activation path for organization and proxy changes, preserving other document
sections, the exact loaded snapshot and expected revision. Do not introduce another configuration store.

`ProxyAwareNativeGitRepositoryProvider` owns bootstrap and active native Git proxy bindings. The schema
does not yet contain persistent proxy entries. Add only the configuration required for editable aliases
and credentials, and connect it to this existing production proxy path. A repository synchronization
remote is a different resource and must not be presented as a transparent proxy.

`OrionKeyMaterial` owns the protected key store and exposes narrow capabilities. Reuse its ownership and
`ConfigurationCipherCapability`: private keys remain purpose/scoped material; secret values remain encrypted
configuration values. SSH code must not receive unrestricted `KeyMaterialService` or private-key export.
New confidential-input support and a narrow material administration capability are justified by importing
secrets over the terminal. New proxy configuration is justified by persistent proxy CRUD. Avoid new
services, modules, general workflow abstractions, and unrelated configuration changes.

Use `secrets` as the common configuration and terminal vocabulary. Reuse `ConfigurationSecretReference`
for scoped references to encrypted values and SSH private keys; extend its existing scope model only as
needed. Passwords and bearer tokens are uses of an encrypted secret value, not separate reference models.
Private-key references resolve to purpose/scoped material owned by `OrionKeyMaterial`; do not add a second
persisted key registry in XML. Safe terminal metadata identifies each secret's scope, ID and supported
kind. Keep HTTPS transport settings separate while TLS, configuration encryption and SSH client keys use
the same material owner through narrow purpose-specific capabilities.

## Required behavior

### Terminal corrections

- Navigation, help and completion run within the cancellable active-operation lifecycle. The reader
  remains available for Ctrl-C and channel close. Propagate cancellation into catalog work and honor
  interruption between repository operations. Suppress late results and restore one prompt.
- Define a width-aware editing viewport. Prefer a bounded horizontal view over multiline redraw state
  if it satisfies long prompts, long input, deletion, cursor motion and shrinking terminal width.
  Account for display-cell width, including wide and combining characters.
- Complete the full token containing the cursor, preserving following arguments and the editor's
  code-point/UTF-16 boundary. Cover beginning, middle and end positions, quoting as supported by the
  existing parser, and supplementary Unicode characters.

### Organizations and Git proxies

- Connect organization, organization-user and organization-repository reads to current configuration.
- Provide organization create, update and delete through the existing command tree. Stable IDs are
  immutable; display names may change. Preserve children on update and reject deletion of nonempty
  organizations unless explicit supported semantics prevent accidental loss.
- Connect proxy list/show to configured transparent proxies; provide create, update and delete with
  stable scoped aliases, sanitized upstream, ref/transport and credential references. Support system
  and organization scope using existing scope rules; preserve inherited credential restrictions.
- Changes survive reload/restart and are applied through the existing native proxy runtime. A command
  must not report active success for a merely in-memory or unusable placeholder. Keep bootstrap
  configuration/material proxies usable when activating or updating the persistent catalog; reject
  destructive mutations of resources required by the running bootstrap configuration if necessary.
- Reject stale revisions, duplicate aliases, invalid upstreams, references to missing credentials and
  forbidden cross-scope credentials. Preserve unrelated configuration and SSH credential updates.
- Use existing application-admin authorization for mutations and sensitive administrative metadata
  until a relevant narrower production rule exists. Preserve existing ACL filtering of domain reads,
  help and completion. Do not invent a permissive organization authorization model.

### Secret references and material import

- Expose discoverable terminal secret commands to list safe metadata and import private keys and opaque
  values used as passwords or tokens. Accept multiline private-key input and hidden secret-value input;
  document supported key formats. Resolve references with scope and required-use checks; do not add
  unsupported secret kinds or a general resolver framework for hypothetical future consumers.
- Enter confidential input only after command validation and authorization. Never echo secret bytes,
  add them to editor history, complete them, or include them in command/audit/error logs. Reject secret
  values supplied as ordinary named/positional command arguments. Bound input size and terminate input
  predictably. Ctrl-C, EOF, channel close and save failures release input and restore the right state.
- Keep secret payloads outside ordinary command-line parsing and completion. Use the smallest explicit
  input/result contract that fits the shared command lifecycle. SSH exec without the required input
  mode returns a structured actionable failure instead of hanging.
- Validate keys before storing; import through a narrow owner capability, with safe alias/purpose/scope
  checks. Support explicit replacement of user-managed material, preserving required server identity,
  TLS and bootstrap material. Do not silently overwrite an existing alias.
- Secret values are encrypted using the existing authenticated configuration envelope and stored in the
  owning `secrets` collection in `orion.xml`. Proxy credentials use the common scoped secret reference
  for both encrypted values and private keys. Preserve keys in the material store and expose both kinds
  through terminal secret metadata without plaintext reads or duplicated key registration.
- Report persistence conflicts/failures through structured command results. Never claim successful
  import when the protected store or encrypted configuration was not durably saved. Ensure failed
  mutations cannot leak into a later unrelated successful save.

## Verification and delivery

Implement production continuation logic before its tests where applicable. Extend behavior tests for
every slice: straightforward success plus cancellation/races, invalid or missing state, forbidden access,
update/delete, reload, stale revision, failed persistence and confidentiality. Include a real SSH flow
for hidden import and a native proxy flow proving imported credentials and persisted aliases are usable.

Use `make run-test MODULE=<module> TEST='<locator>'` for focused Maven checks, outside the sandbox.
The implementation worker owns development verification (`mvn verify -Pdev -T 4`) and `make test` after
code commits. Keep logs and exact verification outcomes. Update the module review to distinguish fixed
findings from still-pending streaming/session attachment work; document actual terminal syntax.
