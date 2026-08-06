# Add Thin-Pack Optimization

Status: todo
Source: follow-up from production pack building.

Add thin-pack optimization for fetch/clone responses after non-thin delta packs
are safe and covered.

## Scope

- [ ] Advertise and honor thin-pack only when omitted bases can be proven from
  visible negotiated haves.
- [ ] Reject or fall back to self-contained packs when shallow, hidden-ref, or
  missing-base cases make thin output unsafe.
- [ ] Cover negotiated thin-pack behavior with native upload-pack tests.
