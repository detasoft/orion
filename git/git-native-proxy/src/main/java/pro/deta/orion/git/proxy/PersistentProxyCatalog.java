package pro.deta.orion.git.proxy;

import java.util.Map;

@FunctionalInterface
public interface PersistentProxyCatalog {
    Map<String, RuntimeGitProxyBinding> load(PersistentProxyCredentialResolver credentialResolver);
}
