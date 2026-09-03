# Harden HTTP Core Contracts

Status: todo
Source: [the module review](../../../../net/http-core/MODULE_REVIEW.md)

Reduce `net/http-core` to explicit route, matching, identity, failure, and
resource-ownership contracts before changing its Jetty execution model.

## Child Tasks

- [ ] [Unify HTTP route invocation](unified-route-invocation/TASK.md)
- [ ] [Centralize typed HTTP route matching](typed-route-matching/TASK.md)
