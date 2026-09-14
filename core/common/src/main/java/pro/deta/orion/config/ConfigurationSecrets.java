package pro.deta.orion.config;

import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.keymaterial.ConfigurationSecretContext;
import pro.deta.orion.keymaterial.ConfigurationSecretEnvelopeCodec;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.ConfigurationSecretReference;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RepositoryAddress;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resolves credentials from the current configuration using owner-bound encryption.
 * Updates consume the supplied characters and return an unpublished candidate;
 * the authorized caller persists it with the configuration's optimistic revision check.
 * Returned resolved characters belong to the caller and must be cleared after use.
 */
public final class ConfigurationSecrets {
    private final Supplier<OrionDocument> current;
    private final ConfigurationCipherCapability cipher;
    private final ConfigurationSecretEnvelopeCodec codec = new ConfigurationSecretEnvelopeCodec();

    public ConfigurationSecrets(Supplier<OrionDocument> current, ConfigurationCipherCapability cipher) {
        this.current = Objects.requireNonNull(current, "current configuration");
        this.cipher = Objects.requireNonNull(cipher, "configuration cipher");
    }

    public char[] resolve(RepositoryAddress repository, ConfigurationSecretReference reference) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(reference, "secret reference");
        OrionDocument document = current.get();
        ConfigurationScope repositoryScope = ConfigurationScope.repository(repository);
        entries(document, Optional.of(repositoryScope));
        ConfigurationScope owner = switch (reference.scope()) {
            case ORGANIZATION -> ConfigurationScope.organization(repository.organizationId());
            case REPOSITORY -> repositoryScope;
        };
        return resolve(document, Optional.of(owner), reference.reference());
    }

    public char[] resolveSystem(String id) {
        return resolve(current.get(), Optional.empty(), id);
    }

    public OrionDocument create(OrionDocument source, ConfigurationScope owner, String id, char[] value) {
        return update(source, checkedOwner(owner, value), id, value, false);
    }

    public OrionDocument replace(OrionDocument source, ConfigurationScope owner, String id, char[] value) {
        return update(source, checkedOwner(owner, value), id, value, true);
    }

    public OrionDocument createSystem(OrionDocument source, String id, char[] value) {
        return update(source, Optional.empty(), id, value, false);
    }

    public OrionDocument replaceSystem(OrionDocument source, String id, char[] value) {
        return update(source, Optional.empty(), id, value, true);
    }

    private char[] resolve(OrionDocument document, Optional<ConfigurationScope> owner, String id) {
        for (ConfigurationSecret secret : entries(document, owner)) {
            if (secret.id().equals(id)) {
                byte[] plaintext = null;
                try {
                    plaintext = cipher.open(codec.parse(secret.envelope()), context(owner, id));
                    return decode(plaintext);
                } catch (GeneralSecurityException | CharacterCodingException failure) {
                    throw new IllegalStateException("Configuration secret cannot be decrypted");
                } finally {
                    if (plaintext != null) {
                        Arrays.fill(plaintext, (byte) 0);
                    }
                }
            }
        }
        throw new IllegalArgumentException("Configuration secret is unavailable in the requested scope");
    }

    private OrionDocument update(
            OrionDocument source,
            Optional<ConfigurationScope> owner,
            String id,
            char[] value,
            boolean replace) {
        Objects.requireNonNull(value, "secret value");
        byte[] plaintext = null;
        try {
            List<ConfigurationSecret> existing = entries(source, owner);
            int position = -1;
            for (int index = 0; index < existing.size(); index++) {
                if (existing.get(index).id().equals(id)) {
                    position = index;
                    break;
                }
            }
            if (replace != (position >= 0)) {
                throw new IllegalArgumentException(replace
                        ? "Cannot replace a missing configuration secret"
                        : "Configuration secret already exists");
            }
            plaintext = encode(value);
            ConfigurationSecret secret = new ConfigurationSecret(id,
                    codec.serialize(cipher.seal(plaintext, context(owner, id))));
            List<ConfigurationSecret> updated = new ArrayList<>(existing);
            if (replace) {
                updated.set(position, secret);
            } else {
                updated.add(secret);
            }
            return withEntries(source, owner, updated);
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Configuration secret is not valid Unicode");
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Configuration secret cannot be encrypted");
        } finally {
            Arrays.fill(value, '\0');
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static Optional<ConfigurationScope> checkedOwner(ConfigurationScope owner, char[] value) {
        if (owner == null) {
            if (value != null) {
                Arrays.fill(value, '\0');
            }
            throw new IllegalArgumentException("Configuration secret owner must be explicit");
        }
        return Optional.of(owner);
    }

    private static List<ConfigurationSecret> entries(
            OrionDocument document,
            Optional<ConfigurationScope> owner) {
        Objects.requireNonNull(document, "configuration");
        if (owner.isEmpty()) {
            return document.system().secrets();
        }
        ConfigurationScope scope = owner.orElseThrow();
        OrionDocument.Organization organization = organization(document, scope.organizationId());
        if (scope.teamId().isEmpty()) {
            return organization.secrets();
        }
        if (scope.repositoryId().isEmpty()) {
            throw new IllegalArgumentException("Team-owned configuration secrets are unsupported");
        }
        for (OrionDocument.Team team : organization.teams()) {
            if (team.id().equals(scope.teamId().orElseThrow())) {
                for (OrionDocument.Repository repository : team.repositories()) {
                    if (repository.id().equals(scope.repositoryId().orElseThrow())) {
                        return repository.secrets();
                    }
                }
            }
        }
        throw new IllegalArgumentException("Configuration secret repository owner is unavailable");
    }

    private static OrionDocument.Organization organization(OrionDocument document, OrganizationId id) {
        for (OrionDocument.Organization organization : document.organizations()) {
            if (organization.id().equals(id)) {
                return organization;
            }
        }
        throw new IllegalArgumentException("Configuration secret organization owner is unavailable");
    }

    private static OrionDocument withEntries(
            OrionDocument document,
            Optional<ConfigurationScope> owner,
            List<ConfigurationSecret> secrets) {
        if (owner.isEmpty()) {
            return new OrionDocument(new OrionDocument.SystemConfiguration(
                    document.system().accessControl(), document.system().https(), secrets),
                    document.organizations());
        }
        ConfigurationScope scope = owner.orElseThrow();
        List<OrionDocument.Organization> organizations = new ArrayList<>();
        for (OrionDocument.Organization organization : document.organizations()) {
            if (!organization.id().equals(scope.organizationId())) {
                organizations.add(organization);
                continue;
            }
            List<OrionDocument.Team> teams = new ArrayList<>();
            for (OrionDocument.Team team : organization.teams()) {
                if (scope.teamId().isEmpty() || !team.id().equals(scope.teamId().orElseThrow())) {
                    teams.add(team);
                    continue;
                }
                List<OrionDocument.Repository> repositories = new ArrayList<>();
                for (OrionDocument.Repository repository : team.repositories()) {
                    repositories.add(repository.id().equals(scope.repositoryId().orElseThrow())
                            ? new OrionDocument.Repository(repository.id(), repository.displayName(),
                            repository.defaultBranch(), repository.policy(), repository.remotes(),
                            repository.grants(), repository.roles(), secrets)
                            : repository);
                }
                teams.add(new OrionDocument.Team(team.id(), team.displayName(), team.grants(),
                        team.roles(), repositories));
            }
            organizations.add(new OrionDocument.Organization(organization.id(), organization.displayName(),
                    organization.users(), organization.grants(), organization.roles(), teams,
                    scope.teamId().isEmpty() ? secrets : organization.secrets()));
        }
        return new OrionDocument(document.system(), organizations);
    }

    private static ConfigurationSecretContext context(Optional<ConfigurationScope> owner, String id) {
        String address = "system";
        if (owner.isPresent()) {
            ConfigurationScope scope = owner.orElseThrow();
            address = (scope.repositoryId().isPresent() ? "repository/" : "organization/") + scope;
        }
        return new ConfigurationSecretContext(address + "/" + id, "credential");
    }

    private static byte[] encode(char[] value) throws CharacterCodingException {
        if (value.length == 0) {
            throw new IllegalArgumentException("Configuration secret must not be empty");
        }
        ByteBuffer bytes = ByteBuffer.allocate(Math.multiplyExact(value.length, 3));
        try {
            var result = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value), bytes, true);
            if (!result.isUnderflow()) {
                result.throwException();
            }
            return Arrays.copyOf(bytes.array(), bytes.position());
        } finally {
            Arrays.fill(bytes.array(), (byte) 0);
        }
    }

    private static char[] decode(byte[] value) throws CharacterCodingException {
        CharBuffer characters = CharBuffer.allocate(value.length);
        try {
            var result = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value), characters, true);
            if (!result.isUnderflow()) {
                result.throwException();
            }
            if (characters.position() == 0) {
                throw new IllegalStateException("Configuration secret is empty");
            }
            return Arrays.copyOf(characters.array(), characters.position());
        } finally {
            Arrays.fill(characters.array(), '\0');
        }
    }
}
