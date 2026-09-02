# Define Material Roles and Bootstrap Ordering

Status: todo

Define the ownership boundary between bootstrap configuration, the protected
material store, the versioned `orion.xml` snapshot, and runtime state.

## Scope

- Model independent asynchronous loading of the material store and `orion.xml`
  from local native Git, followed by one activation barrier.
- Require material availability before decrypting or publishing configuration.
- Define failures for missing, corrupt, incompatible, or mismatched inputs.
- Distinguish cluster-wide material from node-local material and document which
  bootstrap values cannot live inside `orion.xml`.
- Test both completion orders, restart, and failure without partial activation.
