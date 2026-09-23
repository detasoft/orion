package pro.deta.orion.git.proxy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.shell.UnknownCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.client.GitClientOptions;
import pro.deta.orion.git.client.GitClientService;
import pro.deta.orion.git.client.GitClientTransportSession;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
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
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.GitProxyBinding;
import java.net.URI;
import java.util.Set;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapSshTrustTest {
    @ParameterizedTest
    @CsvSource({"configuration,accessControl", "material,keyMaterial"})
    void acceptsUnconfiguredKeyAndPrintsYamlForItsSource(String sourceId, String section) throws Exception {
        try (Fixture fixture = new Fixture()) {
            BootstrapSourceConfig source = fixture.source("");
            fixture.provider.prepareProvisional(sourceId, source);
            assertThat(fixture.authentications.get()).isPositive();
            assertThat(fixture.messages()).contains("not verified", "bootstrap:\n  " + section + ":",
                    "    auth:\n      knownHosts: |\n        " + fixture.key(), "SHA256:");
            assertThat(fixture.messages()).doesNotContain("secret-password");
            assertThat(source.getAuth().get("knownHosts")).isEmpty();
        }
    }

    @Test
    void rejectsMismatchAndPrintsBothExistingAndPresentedKeys() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String previous = PublicKeyEntry.toString(KeyPairGenerator.getInstance("EC")
                    .generateKeyPair().getPublic());
            assertThatThrownBy(() -> fixture.provider.prepareProvisional("configuration", fixture.source(previous)))
                    .isInstanceOf(BootstrapGitProxyException.class);
            assertThat(fixture.authentications).hasValue(0);
            assertThat(fixture.messages()).contains("rejected", "accessControl:", previous, fixture.key(),
                    "Verify", "SHA256:").doesNotContain("secret-password");
        }
    }

    @Test
    void acceptsConfiguredKeyWithoutTrustWarning() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.provider.prepareProvisional("configuration", fixture.source(fixture.key()));
            assertThat(fixture.authentications.get()).isPositive();
            assertThat(fixture.messages()).isEmpty();
        }
    }

    @Test
    void keepsAcceptedKeyInMemoryAndProposesItForXmlAfterActivation() throws Exception {
        try (Fixture fixture = new Fixture(); OrionKeyMaterial material = material()) {
            fixture.provider.prepareProvisional("configuration", fixture.source(""));
            AtomicReference<OrionDocument> current = new AtomicReference<>(
                    OrionDocument.withAccessControl(new AccessControl()));
            ConfigurationSecrets secrets = new ConfigurationSecrets(current::get, material.configurationCipher());
            current.set(fixture.provider.adoptProvisional(current.get(), secrets));
            assertThat(current.get().system().proxies().getFirst().knownHosts()).isEmpty();
            fixture.events.list.clear();
            fixture.provider.activate(current::get, secrets);
            assertThat(fixture.provider.bootstrapChanges(current.get()).values()).singleElement()
                    .satisfies(binding -> assertThat(binding.knownHosts()).containsExactly(fixture.key()));
            fixture.keyPair.set(KeyPairGenerator.getInstance("EC").generateKeyPair());
            int authenticated = fixture.authentications.get();
            assertThat(fixture.provider.retry(current.get().system().proxies().getFirst().alias(),
                    current::get, secrets).isFailure()).isTrue();
            assertThat(fixture.authentications).hasValue(authenticated);
            assertThat(fixture.messages()).contains("rejected");
        }
    }

    @ParameterizedTest
    @CsvSource({"configuration", "material"})
    void yamlUrlAndKeyTakePrecedenceOverAnOlderXmlConnection(String sourceId) throws Exception {
        try (Fixture fixture = new Fixture(); OrionKeyMaterial material = material()) {
            String repository = fixture.provider.prepareProvisional(sourceId, fixture.source(fixture.key()));
            AtomicReference<OrionDocument> current = new AtomicReference<>(
                    OrionDocument.withAccessControl(new AccessControl()));
            ConfigurationSecrets secrets = new ConfigurationSecrets(current::get, material.configurationCipher());
            current.set(fixture.provider.adoptProvisional(current.get(), secrets));
            GitProxyBinding actual = current.get().system().proxies().getFirst();
            String oldKey = PublicKeyEntry.toString(KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic());
            GitProxyBinding previous = new GitProxyBinding(actual.alias(),
                    URI.create("ssh://git@127.0.0.1:1/old.git"), actual.ref(), actual.credentialKind(),
                    actual.secret(), actual.username(), Set.of(oldKey));
            OrionDocument.SystemConfiguration system = current.get().system();
            current.set(new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(),
                    system.https(), system.secrets(), List.of(previous)), current.get().organizations()));
            assertThat(fixture.provider.adoptProvisional(current.get(), secrets)).isSameAs(current.get());
            fixture.provider.activate(current::get, secrets);
            assertThat(fixture.provider.openForRead(repository).isFailure()).isFalse();
            assertThat(fixture.provider.retry(previous.alias(), current::get, secrets).isFailure()).isFalse();
            assertThat(fixture.provider.bootstrapChanges(current.get())).containsEntry(previous, actual);
            assertThat(current.get().system().proxies()).containsExactly(previous);
            assertThat(fixture.messages()).isEmpty();
        }
    }

    private static OrionKeyMaterial material() throws Exception {
        KeyMaterialDescriptor signing = new KeyMaterialDescriptor(new KeyMaterialAlias("signing"),
                KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1),
                KeyMaterialScope.cluster("test"));
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
            return OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options,
                    new SigningMaterialSet(signing, List.of()), 2048, true);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final SshServer server = SshServer.setUpDefaultServer();
        private final AtomicReference<KeyPair> keyPair = new AtomicReference<>(
                KeyPairGenerator.getInstance("EC").generateKeyPair());
        private final AtomicInteger authentications = new AtomicInteger();
        private final Logger logger = (Logger) LoggerFactory.getLogger(BootstrapGitTransportFactory.class);
        private final ListAppender<ILoggingEvent> events = new ListAppender<>();
        private final ProxyAwareNativeGitRepositoryProvider provider;

        private Fixture() throws Exception {
            events.start();
            logger.addAppender(events);
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider((KeyPairProvider) session -> List.of(keyPair.get()));
            server.setPasswordAuthenticator((user, password, session) -> {
                authentications.incrementAndGet();
                return "git".equals(user) && "secret-password".equals(password);
            });
            server.setCommandFactory((channel, command) -> new UnknownCommand(command));
            server.start();
            provider = new ProxyAwareNativeGitRepositoryProvider(new InMemoryNativeGitRepositoryProvider(),
                    new BootstrapSecretResolver(Map.of("SSH_PASSWORD", "secret-password")),
                    (location, transport, repository) -> {
                        try (GitClientTransportSession session = transport.open(GitClientService.UPLOAD_PACK,
                                location.remoteUri(), GitClientOptions.defaults())) {
                            // Opening the exchange exercises real SSH host verification and authentication.
                        }
                    }, (location, transport, repository, received, updates, atomic) -> List.of());
        }

        private String key() {
            return PublicKeyEntry.toString(keyPair.get().getPublic());
        }

        private BootstrapSourceConfig source(String keys) {
            BootstrapSourceConfig source = new BootstrapSourceConfig();
            source.setLocation("git+ssh://git@127.0.0.1:" + server.getPort() + "/repository.git");
            source.setAuth(Map.of("credentialKind", "password", "credential", "env:SSH_PASSWORD",
                    "knownHosts", keys));
            source.setPath("orion.xml");
            return source;
        }

        private String messages() {
            StringBuilder messages = new StringBuilder();
            for (ILoggingEvent event : events.list) {
                messages.append(event.getFormattedMessage()).append('\n');
            }
            return messages.toString();
        }

        @Override
        public void close() throws Exception {
            logger.detachAppender(events);
            events.stop();
            server.stop(true);
        }
    }
}
