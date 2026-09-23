package pro.deta.orion.acl;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OidcProvider;
import pro.deta.orion.schema.orion.OrganizationInvitation;
import pro.deta.orion.schema.orion.OrionDocument;

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
    private final OrionAccessControlServiceImpl acl;
    private final OrionDesiredState desired;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    @Inject
    public OrganizationAccounts(OrionAccessControlServiceImpl acl, OrionDesiredState desired) {
        this.acl = acl;
        this.desired = desired;
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
            for (AccessControl.User user : organization.users()) {
                if (user.getEmail() != null && user.getEmail().equalsIgnoreCase(normalized)) {
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
        for (OrionDocument.Organization organization : desired.current().document().organizations()) {
            if (organization.id().equals(id)) {
                return organization;
            }
        }
        throw new IllegalArgumentException("Organization is unavailable");
    }

    public AccessControl.User linkedUser(OrganizationId organization, String issuer, String subject) {
        AccessControl.User found = null;
        for (AccessControl.User user : organization(organization).users()) {
            for (AccessControl.Credential credential : user.getCredentials()) {
                if (credential.getType() == AccessControl.CredentialType.OIDC_SUBJECT
                        && Objects.equals(issuer, credential.getKeyId())
                        && Objects.equals(subject, credential.getValue())) {
                    if (found != null && !found.getId().equals(user.getId())) {
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
            for (AccessControl.User existing : organization.users()) {
                if (existing.getEmail() != null && existing.getEmail().equalsIgnoreCase(normalized)) {
                    throw new IllegalArgumentException("A user with this email already exists");
                }
                for (AccessControl.Credential credential : existing.getCredentials()) {
                    if (credential.getType() == AccessControl.CredentialType.OIDC_SUBJECT
                            && Objects.equals(issuer, credential.getKeyId())
                            && Objects.equals(subject, credential.getValue())) {
                        throw new IllegalArgumentException("Account is already linked");
                    }
                }
            }
            List<AccessControl.User> users = new ArrayList<>(organization.users());
            users.add(new AccessControl.User(userId, givenName, familyName, normalized,
                    List.of(new AccessControl.Credential(AccessControl.CredentialType.OIDC_SUBJECT, issuer, subject)),
                    List.of(), List.of(new AccessControl.Grant("organization-read", List.of(
                            new AccessControl.GrantExpression(AccessControl.GrantKey.REPOSITORY, id + "/**"))))));
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
        String revision = desired.current().revision().orElseThrow();
        acl.updatePrimaryConfiguration(revision, document -> {
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
        }, new AccessControlSaveRequest(message, author));
    }

    private static OrionDocument.Organization replaceAccounts(OrionDocument.Organization organization,
            List<AccessControl.User> users, List<OrganizationInvitation> invitations) {
        return new OrionDocument.Organization(organization.id(), organization.displayName(), users,
                organization.grants(), organization.roles(), organization.teams(), organization.secrets(),
                organization.oidcProviders(), invitations);
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
