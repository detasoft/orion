package pro.deta.orion.git.s3;

import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
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
import pro.deta.orion.schema.orion.Connection;
import pro.deta.orion.schema.orion.ConnectionReference;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.S3StorageBinding;
import pro.deta.orion.schema.orion.TeamId;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class S3ConfigurationFixture implements AutoCloseable {
    static final OrganizationId ORGANIZATION = new OrganizationId("acme");
    final AtomicReference<OrionDocument> current = new AtomicReference<>(new OrionDocument(
            new OrionDocument.SystemConfiguration(new AccessControl()), List.of(new OrionDocument.Organization(
                    ORGANIZATION, "", List.of(), List.of(), List.of(), List.of(new OrionDocument.Team(
                    new TeamId("dev"), "", List.of(), List.of(), List.of())), List.of(), List.of(), List.of(), List.of()))));
    final S3Transport transport = new S3Transport();
    final InMemoryNativeGitRepositoryProvider local = new InMemoryNativeGitRepositoryProvider();
    final ConfiguredNativeGitRepositoryProvider provider = new ConfiguredNativeGitRepositoryProvider(local, transport);
    final KeyMaterialService material;
    final ConfigurationSecrets secrets;

    S3ConfigurationFixture() throws Exception {
        KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias("configuration-v1"),
                KeyMaterialPurpose.CONFIGURATION_CIPHER, KeyMaterialAlgorithm.AES, new KeyMaterialVersion(1),
                KeyMaterialScope.cluster("test"));
        try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("test-material".toCharArray())) {
            material = KeyMaterialService.open(new InMemoryKeyMaterialContentStore(), options);
        }
        material.generateSecretKeyIfMissing(descriptor, 256);
        material.save();
        secrets = new ConfigurationSecrets(current::get,
                KeyMaterialCapabilities.open(material, List.of(descriptor)).configurationCipher(descriptor));
        provider.activate(current::get, secrets, ignored -> false);
    }

    void connection(boolean system, String name, String endpoint, String region, String id, String key, String token) {
        OrionDocument document = current.get();
        String secret = name + "-key";
        String tokenId = name + "-token";
        document = storeSecret(document, system, secret, key);
        if (token != null) document = storeSecret(document, system, tokenId, token);
        Connection.S3 connection = new Connection.S3(name, Optional.ofNullable(endpoint).map(URI::create), region,
                false, Optional.of(id), Optional.of(secret), token == null ? Optional.empty() : Optional.of(tokenId));
        List<Connection> connections = new ArrayList<>(system ? document.system().connections()
                : document.organizations().getFirst().connections());
        connections.removeIf(value -> value.name().equals(name));
        connections.add(connection);
        if (system) {
            OrionDocument.SystemConfiguration owner = document.system();
            document = new OrionDocument(new OrionDocument.SystemConfiguration(owner.accessControl(), owner.https(),
                    owner.secrets(), owner.proxies(), connections), document.organizations());
        } else {
            OrionDocument.Organization owner = document.organizations().getFirst();
            document = new OrionDocument(document.system(), List.of(new OrionDocument.Organization(owner.id(),
                    owner.displayName(), owner.users(), owner.grants(), owner.roles(), owner.teams(), owner.secrets(),
                    owner.oidcProviders(), owner.invitations(), connections)));
        }
        current.set(document);
    }

    void bind(String repository, boolean system, String connection, String location) {
        OrionDocument document = current.get();
        OrionDocument.Organization organization = document.organizations().getFirst();
        OrionDocument.Team team = organization.teams().getFirst();
        List<OrionDocument.Repository> repositories = new ArrayList<>(team.repositories());
        repositories.removeIf(value -> value.id().value().equals(repository));
        repositories.add(new OrionDocument.Repository(new RepositoryId(repository), "", "refs/heads/main",
                RepositoryPolicy.safeDefaults(), List.of(), List.of(), List.of(), List.of(),
                Optional.of(new S3StorageBinding(new ConnectionReference(system ? ConnectionReference.Scope.SYSTEM
                        : ConnectionReference.Scope.ORGANIZATION, connection), URI.create(location)))));
        current.set(new OrionDocument(document.system(), List.of(new OrionDocument.Organization(organization.id(),
                organization.displayName(), organization.users(), organization.grants(), organization.roles(),
                List.of(new OrionDocument.Team(team.id(), team.displayName(), team.grants(), team.roles(), repositories)),
                organization.secrets(), organization.oidcProviders(), organization.invitations(), organization.connections()))));
    }

    private OrionDocument storeSecret(OrionDocument document, boolean system, String id, String value) {
        boolean present = false;
        for (var secret : system ? document.system().secrets() : document.organizations().getFirst().secrets()) {
            if (secret.id().equals(id)) present = true;
        }
        char[] plaintext = value.toCharArray();
        if (system) return present ? secrets.replaceSystem(document, id, plaintext)
                : secrets.createSystem(document, id, plaintext);
        ConfigurationScope scope = ConfigurationScope.organization(ORGANIZATION);
        return present ? secrets.replace(document, scope, id, plaintext) : secrets.create(document, scope, id, plaintext);
    }

    @Override
    public void close() {
        try {
            transport.close();
        } finally {
            material.close();
        }
    }
}
