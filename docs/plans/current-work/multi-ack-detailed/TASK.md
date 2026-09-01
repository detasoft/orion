# Legacy upload-pack `multi_ack_detailed`

Status: done
Source: follow-up from Git protocol parity audit divergence 2.

Bring legacy upload-pack `multi_ack_detailed` negotiation in line with the
pack protocol before relying on the advertised capability.

- [x] Implement and test `multi_ack_detailed` ACK negotiation, including
      `continue`, `common`, and `ready` responses.
- [x] Either complete the implementation or stop advertising the capability
      until the behavior is protocol-correct.
