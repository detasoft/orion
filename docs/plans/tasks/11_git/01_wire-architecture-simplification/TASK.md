# Simplify Git Wire Architecture

Status: todo

Finish the blocking wire migration and restore clear ownership between the
wire core, Orion server orchestration, and native repository storage.

## Constraints

Canonical object identity precedes moving its consumers. Blocking output
ownership precedes the parser/storage split. Capability policy consumes the
settled composition points and one repository context. The final review checks
the completed boundaries and updates the saved baseline report.
