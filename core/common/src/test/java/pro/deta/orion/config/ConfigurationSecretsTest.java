package pro.deta.orion.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationSecretContext;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelope;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelopeCodec;
import pro.deta.orion.keymaterial.InMemoryKeyMaterialContentStore;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialAlias;
import pro.deta.orion.keymaterial.KeyMaterialCapabilities;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;
import pro.deta.orion.keymaterial.KeyMaterialOptions;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialScope;
import pro.deta.orion.keymaterial.KeyMaterialService;
import pro.deta.orion.keymaterial.KeyMaterialVersion;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.RepositoryAddress;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.TeamId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationSecretsTest {
    private static final RepositoryAddress REPOSITORY = RepositoryAddress.parse("acme/platform/api");
    private static final ConfigurationSecretReference REFERENCE = new ConfigurationSecretReference(
            ConfigurationSecretReference.Scope.REPOSITORY, "github-token");
    private static final KeyMaterialDescriptor CIPHER = new KeyMaterialDescriptor(
            new KeyMaterialAlias("configuration-v1"), KeyMaterialPurpose.CONFIGURATION_CIPHER,
            KeyMaterialAlgorithm.AES, new KeyMaterialVersion(1), KeyMaterialScope.cluster("test"));
    private final InMemoryKeyMaterialContentStore material = new InMemoryKeyMaterialContentStore();
    private final AtomicReference<OrionDocument> current = new AtomicReference<>(document());
    private final AtomicReference<byte[]> cipherInput = new AtomicReference<>();
    private final AtomicReference<byte[]> cipherOutput = new AtomicReference<>();
    private KeyMaterialService keyMaterial;
    private ConfigurationSecrets secrets;

    @BeforeEach
    void open() throws Exception {
        keyMaterial = KeyMaterialService.open(material, options());
        keyMaterial.generateSecretKeyIfMissing(CIPHER, 256);
        keyMaterial.save();
        secrets = resolver(keyMaterial);
    }

    @AfterEach
    void close() {
        keyMaterial.close();
    }

    @Test
    void encryptsAndResolvesAfterXmlAndKeyMaterialReload() throws Exception {
        char[] input = "tøken-🔑\r\n".toCharArray();
        current.set(secrets.create(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", input));
        assertThat(input).containsOnly('\0');
        assertThat(cipherInput.get()).containsOnly((byte) 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(current.get(), output);
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("tøken");
        current.set(OrionXml.read(new ByteArrayInputStream(output.toByteArray())));

        try (KeyMaterialService reopened = KeyMaterialService.open(material, options())) {
            char[] resolved = resolver(reopened).resolve(REPOSITORY, REFERENCE);
            assertThat(resolved).isEqualTo("tøken-🔑\r\n".toCharArray());
            assertThat(cipherOutput.get()).containsOnly((byte) 0);
            resolved[0] = 'x';
            assertThat(resolver(reopened).resolve(REPOSITORY, REFERENCE))
                    .isEqualTo("tøken-🔑\r\n".toCharArray());
        }
    }

    @Test
    void resolvesAnOrganizationSecretOnlyThroughAnExistingDescendant() {
        current.set(secrets.create(current.get(), ConfigurationScope.organization(new OrganizationId("acme")),
                "github-token", "organization-token".toCharArray()));
        ConfigurationSecretReference organization = new ConfigurationSecretReference(
                ConfigurationSecretReference.Scope.ORGANIZATION, "github-token");

        assertThat(secrets.resolve(REPOSITORY, organization)).isEqualTo("organization-token".toCharArray());
        assertThatThrownBy(() -> secrets.resolve(RepositoryAddress.parse("other/platform/api"), organization))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.resolve(
                RepositoryAddress.parse("acme/platform/missing"), organization))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> secrets.resolve(REPOSITORY, REFERENCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsSystemCredentialsSeparateFromRepositoryResolution() {
        current.set(secrets.createSystem(current.get(), "github-token", "system-token".toCharArray()));

        assertThat(secrets.resolveSystem("github-token")).isEqualTo("system-token".toCharArray());
        assertThatThrownBy(() -> secrets.resolve(REPOSITORY, REFERENCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesOtherOwnersWhenReplacingSystemAndOrganizationSecrets() {
        ConfigurationScope organization = ConfigurationScope.organization(new OrganizationId("acme"));
        current.set(secrets.createSystem(current.get(), "github-token", "system-old".toCharArray()));
        current.set(secrets.create(current.get(), organization, "github-token", "org-old".toCharArray()));
        current.set(secrets.create(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", "repository-token".toCharArray()));
        OrionDocument previous = current.get();

        current.set(secrets.replaceSystem(current.get(), "github-token", "system-new".toCharArray()));
        assertThat(current.get().organizations()).isEqualTo(previous.organizations());
        current.set(secrets.replace(current.get(), organization, "github-token", "org-new".toCharArray()));

        assertThat(current.get().system().accessControl()).isEqualTo(previous.system().accessControl());
        assertThat(current.get().organizations().getFirst().teams())
                .isEqualTo(previous.organizations().getFirst().teams());
        assertThat(secrets.resolveSystem("github-token")).isEqualTo("system-new".toCharArray());
        assertThat(secrets.resolve(REPOSITORY, new ConfigurationSecretReference(
                ConfigurationSecretReference.Scope.ORGANIZATION, "github-token")))
                .isEqualTo("org-new".toCharArray());
        assertThat(secrets.resolve(REPOSITORY, REFERENCE)).isEqualTo("repository-token".toCharArray());
    }

    @Test
    void requiresExplicitReplacementAndObservesRotationWithoutRecreatingTheResolver() {
        OrionDocument initial = current.get();
        current.set(secrets.create(initial, ConfigurationScope.repository(REPOSITORY),
                "github-token", "old-token".toCharArray()));
        assertThat(initial).isEqualTo(document());
        char[] duplicate = "must-not-overwrite".toCharArray();
        assertThatThrownBy(() -> secrets.create(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", duplicate)).isInstanceOf(IllegalArgumentException.class);
        assertThat(duplicate).containsOnly('\0');
        assertThat(secrets.resolve(REPOSITORY, REFERENCE)).isEqualTo("old-token".toCharArray());

        OrionDocument candidate = secrets.replace(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", "new-token".toCharArray());
        assertThat(secrets.resolve(REPOSITORY, REFERENCE)).isEqualTo("old-token".toCharArray());
        current.set(candidate);
        assertThat(secrets.resolve(REPOSITORY, REFERENCE)).isEqualTo("new-token".toCharArray());
        assertThat(repository(current.get()).secrets()).hasSize(1);
    }

    @Test
    void rejectsReplacementOfAMissingSecretAndUnsupportedTeamOwnership() {
        char[] missing = "missing".toCharArray();
        assertThatThrownBy(() -> secrets.replace(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", missing)).isInstanceOf(IllegalArgumentException.class);
        assertThat(missing).containsOnly('\0');
        char[] team = "team".toCharArray();
        assertThatThrownBy(() -> secrets.create(current.get(), ConfigurationScope.team(
                new OrganizationId("acme"), new TeamId("platform")), "github-token", team))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(team).containsOnly('\0');
    }

    @Test
    void rejectsCopiedEnvelopesAcrossScopeAndIdentity() {
        current.set(secrets.create(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", "do-not-report".toCharArray()));
        String envelope = repository(current.get()).secrets().getFirst().envelope();
        OrionDocument.SystemConfiguration system = new OrionDocument.SystemConfiguration(
                current.get().system().accessControl(), current.get().system().https(),
                List.of(new ConfigurationSecret("github-token", envelope)));
        current.set(new OrionDocument(system, current.get().organizations()));
        assertThatThrownBy(() -> secrets.resolveSystem("github-token"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertSafe(failure, envelope, "do-not-report"));

        replaceStoredSecret(new ConfigurationSecret("renamed", envelope));
        assertThatThrownBy(() -> secrets.resolve(REPOSITORY, new ConfigurationSecretReference(
                ConfigurationSecretReference.Scope.REPOSITORY, "renamed")))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertSafe(failure, envelope, "do-not-report"));
    }

    @Test
    void authenticatesTheCompleteOwnerAddressForExistingRepositories() {
        current.set(secrets.create(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", "do-not-report".toCharArray()));
        OrionDocument.Organization original = current.get().organizations().getFirst();
        OrionDocument.Organization other = new OrionDocument.Organization(new OrganizationId("other"),
                original.displayName(), original.users(), original.grants(), original.roles(),
                original.teams(), original.secrets());
        current.set(new OrionDocument(current.get().system(), List.of(original, other)));

        assertThat(secrets.resolve(REPOSITORY, REFERENCE)).isEqualTo("do-not-report".toCharArray());
        assertThatThrownBy(() -> secrets.resolve(RepositoryAddress.parse("other/platform/api"), REFERENCE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPlaintextAndTamperedEnvelopesWithoutPrintingTheirContents() {
        current.set(secrets.create(current.get(), ConfigurationScope.repository(REPOSITORY),
                "github-token", "do-not-report".toCharArray()));
        String envelope = repository(current.get()).secrets().getFirst().envelope();
        int ciphertext = envelope.indexOf(".ciphertext=") + ".ciphertext=".length();
        char changed = envelope.charAt(ciphertext) == 'A' ? 'B' : 'A';
        String tampered = envelope.substring(0, ciphertext) + changed + envelope.substring(ciphertext + 1);
        for (String value : List.of("do-not-report", tampered)) {
            replaceStoredSecret(new ConfigurationSecret("github-token", value));
            assertThatThrownBy(() -> secrets.resolve(REPOSITORY, REFERENCE))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> assertSafe(failure, value, "do-not-report"));
        }
    }

    @Test
    void rejectsInvalidUnicodeAndClearsTheSuppliedCharacters() {
        char[] input = {'s', 'e', 'c', 'r', 'e', 't', '\uD800'};
        assertThatThrownBy(() -> secrets.createSystem(current.get(), "github-token", input))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(input).containsOnly('\0');
        assertThat(current.get().system().secrets()).isEmpty();
    }

    @Test
    void rejectsAuthenticatedInvalidUtf8AndClearsTheDecryptedBuffer() throws Exception {
        ConfigurationCipherCapability cipher = KeyMaterialCapabilities.open(keyMaterial, List.of(CIPHER))
                .configurationCipher(CIPHER);
        String envelope = new ConfigurationSecretEnvelopeCodec().serialize(cipher.seal(
                new byte[]{'r', 'a', 'w', '-', 's', 'e', 'c', 'r', 'e', 't', (byte) 0xc3},
                new ConfigurationSecretContext("repository/acme/platform/api/github-token", "credential")));
        replaceStoredSecret(new ConfigurationSecret("github-token", envelope));

        assertThatThrownBy(() -> secrets.resolve(REPOSITORY, REFERENCE))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertSafe(failure, envelope, "raw-secret"));
        assertThat(cipherOutput.get()).containsOnly((byte) 0);
    }

    private ConfigurationSecrets resolver(KeyMaterialService service) throws Exception {
        ConfigurationCipherCapability cipher = KeyMaterialCapabilities.open(service, List.of(CIPHER))
                .configurationCipher(CIPHER);
        return new ConfigurationSecrets(current::get, new ConfigurationCipherCapability() {
            @Override
            public KeyMaterialDescriptor descriptor() {
                return cipher.descriptor();
            }

            @Override
            public ConfigurationSecretEnvelope seal(byte[] plaintext, ConfigurationSecretContext context)
                    throws GeneralSecurityException {
                cipherInput.set(plaintext);
                return cipher.seal(plaintext, context);
            }

            @Override
            public byte[] open(ConfigurationSecretEnvelope envelope, ConfigurationSecretContext context)
                    throws GeneralSecurityException {
                byte[] plaintext = cipher.open(envelope, context);
                cipherOutput.set(plaintext);
                return plaintext;
            }
        });
    }

    private void replaceStoredSecret(ConfigurationSecret secret) {
        OrionDocument.Repository repository = repository(current.get());
        OrionDocument.Repository changed = new OrionDocument.Repository(
                repository.id(), repository.displayName(), repository.defaultBranch(), repository.policy(),
                repository.remotes(), repository.grants(), repository.roles(), List.of(secret));
        OrionDocument.Organization organization = current.get().organizations().getFirst();
        OrionDocument.Team team = organization.teams().getFirst();
        current.set(new OrionDocument(current.get().system(), List.of(new OrionDocument.Organization(
                organization.id(), organization.displayName(), organization.users(), organization.grants(),
                organization.roles(), List.of(new OrionDocument.Team(team.id(), team.displayName(),
                team.grants(), team.roles(), List.of(changed))), organization.secrets()))));
    }

    private static OrionDocument.Repository repository(OrionDocument document) {
        return document.organizations().getFirst().teams().getFirst().repositories().getFirst();
    }

    private static OrionDocument document() {
        OrionDocument.Repository repository = new OrionDocument.Repository(
                new RepositoryId("api"), "API", "refs/heads/main", RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), List.of());
        return new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl()),
                List.of(new OrionDocument.Organization(new OrganizationId("acme"), "Acme", List.of(),
                List.of(), List.of(), List.of(new OrionDocument.Team(new TeamId("platform"), "Platform",
                List.of(), List.of(), List.of(repository))), List.of())));
    }

    private static KeyMaterialOptions options() {
        return KeyMaterialOptions.pkcs12("test-material-password".toCharArray());
    }

    private static void assertSafe(Throwable failure, String... values) {
        StringWriter rendered = new StringWriter();
        failure.printStackTrace(new PrintWriter(rendered));
        assertThat(rendered.toString()).doesNotContain(values);
    }
}
