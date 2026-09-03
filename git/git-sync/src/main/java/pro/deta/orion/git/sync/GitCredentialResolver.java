package pro.deta.orion.git.sync;

import pro.deta.orion.schema.orion.ConfigurationSecretReference;

@FunctionalInterface
public interface GitCredentialResolver {
    char[] resolve(ConfigurationSecretReference reference);
}
