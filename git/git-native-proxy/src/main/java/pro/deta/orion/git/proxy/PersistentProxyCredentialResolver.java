package pro.deta.orion.git.proxy;

@FunctionalInterface
public interface PersistentProxyCredentialResolver {
    char[] resolve(String credentialId);
}
