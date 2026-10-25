package pro.deta.orion.acl;

import pro.deta.orion.internal.ChangeMetadata;
import jakarta.inject.Inject;
import pro.deta.orion.config.OrionConfigurationEditor;
import pro.deta.orion.config.OrionConfigurationModification;
import pro.deta.orion.util.OrionUtils;
import jakarta.inject.Singleton;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.CredentialType;
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.acl.GrantKey;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OidcProvider;
import pro.deta.orion.schema.orion.v2.OrganizationInvitation;
import pro.deta.orion.schema.orion.v2.SystemInvitation;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Stores invitations and consumes them atomically with account creation in the configuration repository. */
@Singleton
public final class OrganizationAccounts {
    private final OrionConfigurationEditor editor;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    @Inject
    public OrganizationAccounts(OrionConfigurationEditor editor) {
        this.editor = editor;
    }

    public InvitationLink invite(OrganizationId organizationId, String email, UserEmail author) {
        String normalized = OrganizationInvitation.normalizeEmail(email);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires = clock.instant().plusSeconds(7 * 86400).getEpochSecond();
        OrganizationInvitation invitation = new OrganizationInvitation(digest(token), normalized, expires);
        update(organizationId, organization -> {
            if (organization.oidcProviders().isEmpty()) {
                throw new IllegalArgumentException("Configure OIDC for this organization first");
            }
            for (User user : organization.users()) {
                if (user.email() != null && user.email().equalsIgnoreCase(normalized)) {
                    throw new IllegalArgumentException("A user with this email already exists");
                }
            }
            List<OrganizationInvitation> invitations = new ArrayList<>();
            for (OrganizationInvitation previous : organization.invitations()) {
                if (previous.expiresAt() > clock.instant().getEpochSecond() && !previous.email().equals(normalized)) {
                    invitations.add(previous);
                }
            }
            if (invitations.size() >= 1000) {
                throw new IllegalArgumentException("Too many pending invitations");
            }
            invitations.add(invitation);
            return replaceAccounts(organization, organization.users(), invitations);
        }, "invite organization user", author);
        return new InvitationLink(token, expires);
    }

    public InvitationLink inviteSystem(String email, String provider,
            List<SystemInvitation.RepositoryAccess> repositories, UserEmail author) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires = clock.instant().plusSeconds(7 * 86400).getEpochSecond();
        SystemInvitation invitation = new SystemInvitation(digest(token), email, expires, provider, repositories);
        updateSystem(system -> {
            boolean configured = false;
            for (OidcProvider configuredProvider : system.oidcProviders()) {
                if (configuredProvider.id().equals(provider)) configured = true;
            }
            if (!configured) throw new IllegalArgumentException("System OIDC provider is unavailable");
            for (User user : system.accessControl().users()) {
                if (user.email() != null && user.email().equalsIgnoreCase(invitation.email())) {
                    throw new IllegalArgumentException("A user with this email already exists");
                }
            }
            List<SystemInvitation> invitations = new ArrayList<>();
            for (SystemInvitation previous : system.invitations()) {
                if (previous.expiresAt() > clock.instant().getEpochSecond()
                        && !previous.email().equals(invitation.email())) invitations.add(previous);
            }
            if (invitations.size() >= 1000) throw new IllegalArgumentException("Too many pending invitations");
            invitations.add(invitation);
            return replaceSystemAccounts(system, system.accessControl().users(), invitations);
        }, "invite system user", author);
        return new InvitationLink(token, expires);
    }

    public SystemInvitation systemInvitation(String token) {
        return systemInvitation(editor.current().result()
                .valueOrFailure("Cannot read configuration document").system(), token);
    }

    private SystemInvitation systemInvitation(OrionDocument.SystemConfiguration system, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invitation is unavailable");
        }
        String hash = digest(token);
        for (SystemInvitation invitation : system.invitations()) {
            if (invitation.tokenHash().equals(hash) && invitation.expiresAt() > clock.instant().getEpochSecond()) {
                return invitation;
            }
        }
        throw new IllegalArgumentException("Invitation is unavailable");
    }

    public String acceptSystem(String token, String email, OidcProvider provider, String subject,
            String first, String last) {
        String normalized = OrganizationInvitation.normalizeEmail(email);
        String givenName = profileName(first, true);
        String familyName = profileName(last, false);
        String userId = "u-" + UUID.randomUUID();
        String issuer = provider.issuer().toString();
        updateSystem(system -> {
            SystemInvitation invitation = systemInvitation(system, token);
            if (!system.oidcProviders().contains(provider) || !invitation.provider().equals(provider.id())
                    || !invitation.email().equals(normalized)) {
                throw new IllegalArgumentException("Invitation identity or provider does not match");
            }
            for (User existing : system.accessControl().users()) {
                if (existing.email() != null && existing.email().equalsIgnoreCase(normalized)) {
                    throw new IllegalArgumentException("A user with this email already exists");
                }
                for (Credential credential : existing.credentials()) {
                    if (credential.type() == CredentialType.OIDC_SUBJECT
                            && issuer.equals(credential.keyId()) && subject.equals(credential.value())) {
                        throw new IllegalArgumentException("Account is already linked");
                    }
                }
            }
            List<Grant> grants = new ArrayList<>();
            for (SystemInvitation.RepositoryAccess access : invitation.repositories()) {
                List<GrantExpression> expressions = new ArrayList<>();
                expressions.add(new GrantExpression(GrantKey.REPOSITORY, access.repository()));
                expressions.add(new GrantExpression(GrantKey.READ, "true"));
                if (access.readWrite()) expressions.add(new GrantExpression(GrantKey.READ_WRITE, "true"));
                grants.add(new Grant("invitation-" + grants.size(), expressions));
            }
            List<User> users = new ArrayList<>(system.accessControl().users());
            users.add(new User(userId, givenName, familyName, normalized,
                    List.of(new Credential(CredentialType.OIDC_SUBJECT, issuer, subject)), List.of(), grants));
            List<SystemInvitation> invitations = new ArrayList<>(system.invitations());
            invitations.remove(invitation);
            return replaceSystemAccounts(system, users, invitations);
        }, "accept system invitation", new UserEmail(userId, normalized));
        return userId;
    }

    private void updateSystem(UnaryOperator<OrionDocument.SystemConfiguration> operation,
            String message, UserEmail author) {
        OrionConfigurationModification edit = editor.edit(editor.current().revision(),
                new ChangeMetadata(author, message))
                .update(document -> new OrionDocument(operation.apply(document.system()), document.organizations()));
        edit.apply();
        if (!OrionUtils.waitForCondition(() -> editor.current().revision().equals(edit.revision()))) {
            throw new IllegalStateException("Saved system account configuration was not activated");
        }
    }

    private static OrionDocument.SystemConfiguration replaceSystemAccounts(
            OrionDocument.SystemConfiguration system, List<User> users, List<SystemInvitation> invitations) {
        AccessControl acl = system.accessControl();
        return new OrionDocument.SystemConfiguration(new AccessControl(users, acl.roles(), acl.grants()),
                system.https(), system.secrets(), system.proxies(), system.connections(), system.oidcProviders(),
                invitations);
    }

    public OrganizationInvitation invitation(OrganizationId organization, String token) {
        return invitation(organization(organization), token);
    }

    private OrganizationInvitation invitation(OrionDocument.Organization organization, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invitation is unavailable");
        }
        String hash = digest(token);
        for (OrganizationInvitation invitation : organization.invitations()) {
            if (invitation.tokenHash().equals(hash) && invitation.expiresAt() > clock.instant().getEpochSecond()) {
                return invitation;
            }
        }
        throw new IllegalArgumentException("Invitation is unavailable");
    }

    public OrionDocument.Organization organization(OrganizationId id) {
        for (OrionDocument.Organization organization : editor.current().result()
                .valueOrFailure("Cannot read configuration document")
                .organizations()) {
            if (organization.id().equals(id)) {
                return organization;
            }
        }
        throw new IllegalArgumentException("Organization is unavailable");
    }

    public User linkedUser(OrganizationId organization, String issuer, String subject) {
        User found = null;
        for (User user : organization(organization).users()) {
            for (Credential credential : user.credentials()) {
                if (credential.type() == CredentialType.OIDC_SUBJECT
                        && Objects.equals(issuer, credential.keyId())
                        && Objects.equals(subject, credential.value())) {
                    if (found != null && !found.id().equals(user.id())) {
                        throw new IllegalArgumentException("Ambiguous organization account");
                    }
                    found = user;
                }
            }
        }
        return found;
    }

    public String accept(OrganizationId id, String token, String email, OidcProvider provider, String subject,
            String first, String last) {
        String issuer = provider.issuer().toString();
        String normalized = OrganizationInvitation.normalizeEmail(email);
        String userId = "u-" + UUID.randomUUID();
        String givenName = profileName(first, true);
        String familyName = profileName(last, false);
        update(id, organization -> {
            if (!organization.oidcProviders().contains(provider)) {
                throw new IllegalArgumentException("OIDC configuration changed");
            }
            OrganizationInvitation invitation = invitation(organization, token);
            if (!invitation.email().equals(normalized)) {
                throw new IllegalArgumentException("Invitation email does not match");
            }
            for (User existing : organization.users()) {
                if (existing.email() != null && existing.email().equalsIgnoreCase(normalized)) {
                    throw new IllegalArgumentException("A user with this email already exists");
                }
                for (Credential credential : existing.credentials()) {
                    if (credential.type() == CredentialType.OIDC_SUBJECT
                            && Objects.equals(issuer, credential.keyId())
                            && Objects.equals(subject, credential.value())) {
                        throw new IllegalArgumentException("Account is already linked");
                    }
                }
            }
            List<User> users = new ArrayList<>(organization.users());
            users.add(new User(userId, givenName, familyName, normalized,
                    List.of(new Credential(CredentialType.OIDC_SUBJECT, issuer, subject)),
                    List.of(), List.of(new Grant("organization-read", List.of(
                            new GrantExpression(GrantKey.REPOSITORY, id + "/**"))))));
            List<OrganizationInvitation> invitations = new ArrayList<>(organization.invitations());
            invitations.remove(invitation);
            return replaceAccounts(organization, users, invitations);
        }, "accept organization invitation", new UserEmail(userId, normalized));
        return userId;
    }

    private static String profileName(String name, boolean required) {
        String value = name == null ? "" : name.strip();
        if (value.length() > 100 || required && value.isEmpty()
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid profile name");
        }
        return value;
    }

    private void update(OrganizationId id, UnaryOperator<OrionDocument.Organization> operation,
            String message, UserEmail author) {
        OrionRevision revision = editor.current().revision();
        editor.edit(revision, new ChangeMetadata(author, message)).update(document -> {
            List<OrionDocument.Organization> organizations = new ArrayList<>();
            boolean found = false;
            for (OrionDocument.Organization organization : document.organizations()) {
                if (organization.id().equals(id)) {
                    organizations.add(operation.apply(organization));
                    found = true;
                } else {
                    organizations.add(organization);
                }
            }
            if (!found) {
                throw new IllegalArgumentException("Organization is unavailable");
            }
            return new OrionDocument(document.system(), organizations);
        }).apply();
    }

    private static OrionDocument.Organization replaceAccounts(OrionDocument.Organization organization,
            List<User> users, List<OrganizationInvitation> invitations) {
        return new OrionDocument.Organization(organization.id(), organization.displayName(), users,
                organization.grants(), organization.roles(), organization.teams(), organization.secrets(),
                organization.oidcProviders(), invitations, organization.connections());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record InvitationLink(String token, long expiresAt) {
        @Override
        public String toString() {
            return "InvitationLink[expiresAt=" + expiresAt + "]";
        }
    }
}
