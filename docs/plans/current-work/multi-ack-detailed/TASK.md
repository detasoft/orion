# Legacy upload-pack `multi_ack_detailed`

Status: planned
Source: follow-up from Git protocol parity audit divergence 2.

Bring legacy upload-pack `multi_ack_detailed` negotiation in line with the
pack protocol before relying on the advertised capability.

- [ ] Implement and test `multi_ack_detailed` ACK negotiation, including
      `continue`, `common`, and `ready` responses.
- [ ] Either complete the implementation or stop advertising the capability
      until the behavior is protocol-correct.
