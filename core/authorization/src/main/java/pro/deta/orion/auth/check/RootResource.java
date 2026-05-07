package pro.deta.orion.auth.check;

/**
 * Top-level protected resource. Root resources do not have a parent authorization target, so rules that
 * evaluate them must be complete on their own.
 */
public interface RootResource extends ProtectedResource {
}
