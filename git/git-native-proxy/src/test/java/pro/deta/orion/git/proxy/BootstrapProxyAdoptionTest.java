package pro.deta.orion.git.proxy;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import pro.deta.orion.keymaterial.SigningMaterialSet;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.BootstrapSourceConfig;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.GitCredentialKind;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RemoteAlias;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapProxyAdoptionTest {
    @Test
    void adoptsSharedSourcesOnceAndPreservesTheirPrivateRepositoryHandles() throws Exception {
        try (OrionKeyMaterial material = material()) {
            AtomicReference<OrionDocument> current = new AtomicReference<>(empty());
            ConfigurationSecrets secrets = new ConfigurationSecrets(current::get, material.configurationCipher());
            var provider = provider("external-token");
            String name = provider.prepareProvisional("material", source("HTTPS://GIT.EXAMPLE:443/repo"));
            String configurationName = provider.prepareProvisional(
                    "configuration", source("https://git.example/repo"));
            assertThat(configurationName).isEqualTo(name);
            NativeGitRepository materialRepository = provider.openForRead(name).valueOrFailure("material");
            NativeGitRepository configurationRepository = provider.openForRead(configurationName)
                    .valueOrFailure("configuration");

            OrionDocument adopted = provider.adoptProvisional(current.get(), secrets);

            assertThat(current.get().system().proxies()).isEmpty();
            assertThat(adopted.system().proxies()).hasSize(1);
            assertThat(adopted.system().proxies().getFirst().alias()).isEqualTo(new RemoteAlias("configuration"));
            assertThat(adopted.system().secrets()).hasSize(1);
            assertThat(adopted.toString()).doesNotContain("external-token", name);
            current.set(adopted);
            char[] value = secrets.resolveSystem(adopted.system().proxies().getFirst().secret().orElseThrow());
            try {
                assertThat(value).isEqualTo("external-token".toCharArray());
            } finally {
                Arrays.fill(value, '\0');
            }
            assertThat(provider.adoptProvisional(adopted, secrets)).isSameAs(adopted);
            assertThat(configurationRepository.refs()).isEmpty();
            assertThat(materialRepository.refs()).isEmpty();
            assertThat(provider.repositoryNames()).isEmpty();
            assertThat(provider.isPublicRepositoryName(name)).isFalse();
        }
    }

    @Test
    void keepsAnExistingAliasAndRotatedCredentialOnRestart() throws Exception {
        try (OrionKeyMaterial material = material()) {
            AtomicReference<OrionDocument> current = new AtomicReference<>(empty());
            ConfigurationSecrets secrets = new ConfigurationSecrets(current::get, material.configurationCipher());
            current.set(secrets.createSystem(current.get(), "managed-token", "rotated-token".toCharArray()));
            GitProxyBinding binding = binding("operator-selected", "https://git.example/repo", "managed-token");
            current.set(withProxy(current.get(), binding));
            var provider = provider("external-token");
            provider.prepareProvisional("configuration", source("HTTPS://GIT.EXAMPLE:443/repo"));
            assertThat(provider.adoptProvisional(current.get(), secrets)).isSameAs(current.get());
            assertThat(current.get().system().proxies()).containsExactly(binding);
        }
    }

    @Test
    void rejectsAnOccupiedAliasBeforeAddingSecrets() throws Exception {
        try (OrionKeyMaterial material = material()) {
            var current = new AtomicReference<>(empty());
            ConfigurationSecrets secrets = new ConfigurationSecrets(current::get, material.configurationCipher());
            current.set(secrets.createSystem(current.get(), "existing", "existing-token".toCharArray()));
            current.set(withProxy(current.get(), binding("configuration", "https://git.example/other", "existing")));
            var provider = provider("external-token");
            provider.prepareProvisional("configuration", source("https://git.example/repo"));
            assertThatThrownBy(() -> provider.adoptProvisional(current.get(), secrets))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("alias");
            assertThat(current.get().system().secrets()).hasSize(1);
        }
    }

    @Test
    void rejectsAnOccupiedGeneratedSecretIdentity() throws Exception {
        try (OrionKeyMaterial material = material()) {
            var current = new AtomicReference<>(empty());
            ConfigurationSecrets secrets = new ConfigurationSecrets(current::get, material.configurationCipher());
            current.set(secrets.createSystem(current.get(), "configuration-credential", "other".toCharArray()));
            var provider = provider("external-token");
            provider.prepareProvisional("configuration", source("https://git.example/repo"));
            assertThatThrownBy(() -> provider.adoptProvisional(current.get(), secrets))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("secret");
            assertThat(current.get().system().proxies()).isEmpty();
        }
    }

    @Test
    void rejectsUndecryptableExistingCredentialsWithoutReplacingThem() throws Exception {
        try (OrionKeyMaterial material = material()) {
            OrionDocument current = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                    Optional.empty(), List.of(new ConfigurationSecret("token", "invalid-envelope")),
                    List.of(binding("configuration", "https://git.example/repo", "token"))), List.of());
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> current, material.configurationCipher());
            var provider = provider("external-token");
            provider.prepareProvisional("configuration", source("https://git.example/repo"));
            assertThatThrownBy(() -> provider.adoptProvisional(current, secrets))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("invalid-envelope");
        }
    }

    private static OrionDocument empty() {
        return OrionDocument.withAccessControl(new AccessControl());
    }

    private static OrionDocument withProxy(OrionDocument current, GitProxyBinding proxy) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(current.system().accessControl(),
                current.system().https(), current.system().secrets(), List.of(proxy)), current.organizations());
    }

    private static GitProxyBinding binding(String alias, String upstream, String secret) {
        return new GitProxyBinding(new RemoteAlias(alias), URI.create(upstream), "main",
                GitCredentialKind.TOKEN, Optional.of(secret), Optional.empty(), Optional.empty());
    }

    private static BootstrapSourceConfig source(String upstream) {
        BootstrapSourceConfig source = new BootstrapSourceConfig();
        source.setLocation("git+" + upstream);
        source.setPath("orion.xml");
        source.setRef("main");
        source.setAuth(Map.of("credentialKind", "token", "credential", "env:TOKEN"));
        return source;
    }

    private static ProxyAwareNativeGitRepositoryProvider provider(String token) {
        return new ProxyAwareNativeGitRepositoryProvider(new InMemoryNativeGitRepositoryProvider(),
                new BootstrapSecretResolver(Map.of("TOKEN", token)), (location, transport, repository) -> { },
                (location, transport, repository, received, updates, atomic) -> List.of());
    }

    private static OrionKeyMaterial material() throws Exception {
        var signing = new KeyMaterialDescriptor(new KeyMaterialAlias("signing"), KeyMaterialPurpose.SERVER_SIGNING,
                KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1), KeyMaterialScope.cluster("test"));
        try (var options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
            return OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options,
                    new SigningMaterialSet(signing, List.of()), 2048, true);
        }
    }
}
