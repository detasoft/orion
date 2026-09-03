# Simplify Git Client Architecture

Status: todo

Make one remote Git operation use one coherent advertisement and exchange,
model Smart HTTP without pretending it is a continuous duplex stream, and
keep client failures and timeout ownership factual.

## Child Tasks

- [ ] [Plan each request inside one remote session](single-session-request-planning/TASK.md)
- [ ] [Model explicit Git client exchange phases](phase-aware-transport-exchange/TASK.md)
- [ ] [Make Git client failures factual](factual-failure-model/TASK.md)
- [ ] [Replace per-I/O timeout scheduling](session-inactivity-timeouts/TASK.md)
- [ ] [Verify Smart HTTP and SSH behavioral parity](smart-http-ssh-behavior/TASK.md)
- [ ] [Re-audit removable Git client machinery](post-simplification-removal-review/TASK.md)

## Constraints

- Public Git client API compatibility is not required before production
  consumers appear; prefer the final contract over deprecated bridges.
- Replace affected contracts directly; do not retain compatibility overloads,
  adapters, or parallel old and new execution paths.
- Preserve blocking pack streaming, bounded backpressure, size limits, and one
  virtual thread per complete remote operation.
- Characterize equivalent Smart HTTP and SSH behavior wherever their physical
  request and connection lifecycles differ.
