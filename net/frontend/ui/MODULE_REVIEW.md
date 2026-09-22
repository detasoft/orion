# Module Review: `net/frontend/ui`

## 4. Default credential replacement removes SSH private-key line breaks

**Problem.** Open Replace credential for an alias using an SSH private key and leave Authentication at
Keep current method. The form displays a password input, which strips line breaks from a pasted multiline key.
Submitting can persist that damaged value before the subsequent upstream synchronization fails.

**Sources.** [`RemoteAliases.vue`](src/components/RemoteAliases.vue) initializes replacement `credentialKind` to
an empty string in `openEditor`, renders a textarea only for explicit `PRIVATE_KEY`, and otherwise uses a
password input. `submit` omits an empty credential kind while sending the credential value.
[`OrionAdminProxiesRoute`](../../http-core/src/main/java/pro/deta/orion/transport/http/OrionAdminProxiesRoute.java)
retains the existing kind when the request omits it and persists the new secret before calling `provider.retry`.
The list response does not expose the existing credential kind to the form.

**Documented behavior.** The form explicitly offers Keep current method and supports SSH private keys.
The input type currently prevents that combination from preserving a multiline credential.

**Contract.** Replacing a credential while retaining its kind must preserve the supplied secret, including
private-key line breaks. Continue clearing the draft after submission and avoid returning stored secret values.

**Minimal repair.** Use a multiline input for the unspecified existing kind as well as explicit PRIVATE_KEY.
Cover replacement through the default selection and verify that the request retains the exact multiline key
without sending a replacement kind.

**Alternatives and consequences.** Requiring explicit kind selection changes the current keep-method flow.
Returning credential-kind metadata enables a specific editor but requires an API change. A textarea exposes
the entered value visually, as the explicit private-key editor already does.

**Confidence.** High from the form/submission/server path and the installed DOM implementation's password-input
newline sanitization. No live upstream key rotation was executed.

**Priority signals.** Importance: high because an ordinary replacement can overwrite a working credential.
Repair ease: high because the existing multiline editor can cover the unspecified kind without API changes.
