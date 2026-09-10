# Simplify Git Client Architecture

Status: todo

Make one remote Git operation use one coherent advertisement and exchange,
model Smart HTTP without pretending it is a continuous duplex stream, and
keep client failures and timeout ownership factual.

## Constraints

- Public Git client API compatibility is not required before production
  consumers appear; prefer the final contract over deprecated bridges.
- Replace affected contracts directly; do not retain compatibility overloads,
  adapters, or parallel old and new execution paths.
- Preserve blocking pack streaming, bounded backpressure, size limits, and one
  virtual thread per complete remote operation.
- Characterize equivalent Smart HTTP and SSH behavior wherever their physical
  request and connection lifecycles differ.
