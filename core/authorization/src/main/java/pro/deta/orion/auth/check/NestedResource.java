package pro.deta.orion.auth.check;

/**
 * Protected resource that is scoped by a parent resource. Nested resources make hierarchy explicit, so a
 * branch or protocol operation cannot be accidentally modeled as an independent top-level target.
 */
public interface NestedResource<P extends ProtectedResource> extends ProtectedResource {
    P parentResource();
}
