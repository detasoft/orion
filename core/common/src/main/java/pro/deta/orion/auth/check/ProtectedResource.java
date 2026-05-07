package pro.deta.orion.auth.check;

/**
 * Common marker for an authorization target. Use RootResource for independent top-level targets and
 * NestedResource for targets that are only meaningful inside another protected resource.
 */
public interface ProtectedResource {
    String describe();
}
