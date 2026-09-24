package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.acl.OrganizationAccounts;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.*;
import pro.deta.orion.auth.*;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.RepositoryAccessRules;
import pro.deta.orion.auth.check.resource.RepositoryResource;
import pro.deta.orion.config.*;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.orion.*;
import pro.deta.orion.util.Result;

import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class OrionOidcOnboardingTest {
    @Test
    void sessionSurvivesRestartAndLogoutIsDurable() throws Exception {
        try (Fixture f = new Fixture()) {
            Reply login = f.signIn("corporate", f.invite("acme"));
            f.clock.now = f.clock.now.plusSeconds(47 * 3600);
            f.activity("GET", "/api/admin/repositories", login.json.path("token").asText());
            f.restart();
            f.clock.now = f.clock.now.plusSeconds(2 * 3600);
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(200);
            assertThat(f.post("logout", Map.of(), null).status).isEqualTo(204);
            f.restart();
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @Test
    void modifiedSessionIsRejectedAfterRestart() throws Exception {
        try (Fixture f = new Fixture()) {
            f.signIn("corporate", f.invite("acme"));
            try (DirectoryStream<Path> files = Files.newDirectoryStream(f.sessions)) {
                for (Path file : files) {
                    String encrypted = Files.readString(file);
                    assertThat(encrypted).doesNotContain("alice", "corporate", f.sessionCookie);
                    Files.writeString(file, encrypted.substring(0, encrypted.length() - 5) + "AAAAA");
                }
            }
            f.restart();
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @Test
    void copiedSessionCannotReplaceAnotherBrowsersSession() throws Exception {
        try (Fixture f = new Fixture()) {
            f.signIn("corporate", f.invite("acme"));
            Path first;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(f.sessions)) {
                first = files.iterator().next();
            }
            byte[] encrypted = Files.readAllBytes(first);
            f.sessionCookie = null;
            f.signIn("corporate", "");
            try (DirectoryStream<Path> files = Files.newDirectoryStream(f.sessions)) {
                for (Path file : files) {
                    if (!file.equals(first)) Files.write(file, encrypted);
                }
            }
            f.restart();
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @Test
    void failedSessionDeletionDoesNotReportSuccessfulLogout() throws Exception {
        try (Fixture f = new Fixture()) {
            f.signIn("corporate", f.invite("acme"));
            Path file;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(f.sessions)) {
                file = files.iterator().next();
            }
            byte[] encrypted = Files.readAllBytes(file);
            Files.delete(file);
            Files.createDirectory(file);
            Path blocker = Files.writeString(file.resolve("blocker"), "blocked");
            try {
                assertThat(f.post("logout", Map.of(), null).status).isEqualTo(400);
            } finally {
                Files.delete(blocker);
                Files.delete(file);
                Files.write(file, encrypted);
            }
            assertThat(f.post("logout", Map.of(), null).status).isEqualTo(204);
            f.restart();
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @Test
    void expiredSessionIsNotRestored() throws Exception {
        try (Fixture f = new Fixture()) {
            f.signIn("corporate", f.invite("acme"));
            f.clock.now = f.clock.now.plusSeconds(48 * 3600);
            f.restart();
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(f.sessions)) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void adminRejectsInvalidTimeoutsWithoutSavingTheProvider() throws Exception {
        try (Fixture f = new Fixture()) {
            String revision = f.desired.current().revision().orElseThrow();
            Map<String, Object> input = new HashMap<>(Map.of("organization", "acme", "id", "invalid",
                    "issuer", f.issuer.toString(), "clientId", "client", "clientSecret", "secret",
                    "revision", revision, "reauthenticationTimeoutSeconds", 0));
            for (Object invalid : List.of(0, -1, 1.5, "3600", Long.MAX_VALUE)) {
                input.put("idleTimeoutSeconds", invalid);
                assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(400);
                assertThat(f.desired.current().revision()).contains(revision);
            }
            input.put("idleTimeoutSeconds", 172800);
            input.put("reauthenticationTimeoutSeconds", -1);
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(400);
            assertThat(f.desired.current().revision()).contains(revision);
        }
    }

    @Test
    void aDifferentOrganizationsTokenCannotExtendTheSession() throws Exception {
        try (Fixture f = new Fixture()) {
            f.signIn("corporate", f.invite("acme"));
            String acmeCookie = f.sessionCookie;
            f.sessionCookie = null;
            Login other = f.start("default", f.invite("default"));
            Reply complete = f.post("complete", Map.of("ticket", f.callback(other), "first", "Alice"), other.cookie);
            f.sessionCookie = acmeCookie;
            f.clock.now = f.clock.now.plusSeconds(47 * 3600);
            assertThat(f.activity("GET", "/api/admin/repositories", complete.json.path("token").asText()).headers)
                    .doesNotContainKey("Set-Cookie");
            f.clock.now = f.clock.now.plusSeconds(3600);
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @Test
    void authenticatedRequestsExtendOnlyTheirOwnSessionBeyondSevenDays() throws Exception {
        try (Fixture f = new Fixture()) {
            Reply first = f.signIn("corporate", f.invite("acme"));
            String firstCookie = f.sessionCookie;
            f.sessionCookie = null;
            f.signIn("corporate", "");
            String idleCookie = f.sessionCookie;
            f.sessionCookie = firstCookie;
            String token = first.json.path("token").asText();
            for (int day = 0; day < 9; day++) {
                f.clock.now = f.clock.now.plusSeconds(86400);
                Reply activity = f.activity("GET", "/api/admin/repositories", token);
                assertThat(activity.headers.get("Set-Cookie")).contains("Max-Age=172800");
                assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(200);
            }
            f.sessionCookie = idleCookie;
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/auth/oidc/refresh", "/api/health",
            "/api/admin/lifecycle/state", "/assets/app.js", "OPTIONS", "HEAD", "anonymous"})
    void excludedRequestsDoNotKeepSessionsAlive(String excluded) throws Exception {
        try (Fixture f = new Fixture()) {
            Reply login = f.signIn("corporate", f.invite("acme"));
            f.clock.now = f.clock.now.plusSeconds(47 * 3600);
            String method = excluded.equals("OPTIONS") || excluded.equals("HEAD") ? excluded : "GET";
            String path = excluded.startsWith("/") ? excluded : "/api/admin/repositories";
            String token = excluded.equals("anonymous") ? "" : login.json.path("token").asText();
            f.activity(method, path, token);
            f.clock.now = f.clock.now.plusSeconds(3600);
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
            assertThat(f.activity("GET", "/api/admin/repositories", token).headers).doesNotContainKey("Set-Cookie");
        }
    }

    @Test
    void providerPoliciesAreIndependentAndAbsoluteExpiryCapsIssuedTokens() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveProvider("short", 3600, 120);
            f.authenticationTime = f.clock.now.getEpochSecond();
            Reply limited = f.signIn("short", f.invite("acme"));
            assertThat(f.authorizationParameters).containsEntry("max_age", "0");
            String limitedCookie = f.sessionCookie;
            JWTClaimsSet claims = JWTParser.parse(limited.json.path("token").asText()).getJWTClaimsSet();
            assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(120000);
            f.sessionCookie = null;
            f.signIn("corporate", "");
            assertThat(f.authorizationParameters).doesNotContainKey("max_age");
            String unlimitedCookie = f.sessionCookie;
            f.sessionCookie = limitedCookie;
            f.clock.now = f.clock.now.plusSeconds(110);
            f.activity("GET", "/api/admin/repositories", limited.json.path("token").asText());
            Reply renewal = f.post("refresh", Map.of(), null);
            assertThat(renewal.status).isEqualTo(200);
            claims = JWTParser.parse(renewal.json.path("token").asText()).getJWTClaimsSet();
            assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(10000);
            f.restart();
            f.clock.now = f.clock.now.plusSeconds(10);
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
            f.sessionCookie = unlimitedCookie;
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(200);
        }
    }

    @Test
    void mandatoryReauthenticationRejectsMissingAndStaleProviderAuthenticationTime() throws Exception {
        try (Fixture f = new Fixture()) {
            f.saveProvider("short", 172800, 604800);
            String invite = f.invite("acme");
            for (Long time : Arrays.asList(null, f.clock.now.minusSeconds(300).getEpochSecond(),
                    f.clock.now.plusSeconds(300).getEpochSecond())) {
                f.authenticationTime = time;
                Login login = f.start("acme", invite, "short");
                assertThat(f.callbackReply(login, login.cookie).status).isEqualTo(400);
            }
            f.authenticationTime = f.clock.now.getEpochSecond();
            assertThat(f.signIn("short", invite).status).isEqualTo(200);
        }
    }

    @Test
    void backgroundRenewalDoesNotExtendTheIdleDeadline() throws Exception {
        try (Fixture f = new Fixture()) {
            Login login = f.start("acme", f.invite("acme"));
            Reply complete = f.post("complete", Map.of("ticket", f.callback(login), "first", "Alice"), login.cookie);
            f.sessionCookie = complete.headers.get("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
            f.clock.now = f.clock.now.plusSeconds(47 * 60 * 60);
            Reply refreshed = f.post("refresh", Map.of(), null);
            assertThat(refreshed.status).isEqualTo(200);
            assertThat(refreshed.headers.get("Set-Cookie")).contains("Max-Age=3600");
            f.clock.now = f.clock.now.plusSeconds(60 * 60);
            Reply expired = f.post("refresh", Map.of(), null);
            assertThat(expired.status).isEqualTo(401);
            assertThat(expired.headers.get("Set-Cookie")).contains("Max-Age=0");
        }
    }

    @Test
    void browserSessionRenewsTokensAndLogoutRevokesTheCookie() throws Exception {
        try (Fixture f = new Fixture()) {
            Login login = f.start("acme", f.invite("acme"));
            Reply complete = f.post("complete", Map.of("ticket", f.callback(login), "first", "Alice"), login.cookie);
            String header = complete.headers.get("Set-Cookie");
            assertThat(header).startsWith("__Host-orion-session=")
                    .contains("HttpOnly", "Secure", "SameSite=Strict", "Max-Age=172800");
            f.sessionCookie = header.split(";", 2)[0].split("=", 2)[1];
            Reply refreshed = f.post("refresh", Map.of(), null);
            assertThat(refreshed.status).isEqualTo(200);
            assertThat(refreshed.headers.get("Cache-Control")).isEqualTo("no-store");
            assertThat(refreshed.json.path("token").asText()).isNotEqualTo(complete.json.path("token").asText());
            TokenAuthenticationResult.Success authenticated = (TokenAuthenticationResult.Success)
                    f.acl.verifyToken(refreshed.json.path("token").asText().getBytes(StandardCharsets.UTF_8));
            assertThat(authenticated.userIdentity().getOrganizationId()).contains(new OrganizationId("acme"));
            assertThat(refreshed.json.path("userId").asText()).isEqualTo(authenticated.userIdentity().getUserId());
            assertThat(f.post("logout", Map.of(), null).status).isEqualTo(204);
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "binding", "provider"})
    void sessionRefreshRequiresTheBrowserOriginAndCurrentAccount(String removed) throws Exception {
        try (Fixture f = new Fixture()) {
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
            Login login = f.start("acme", f.invite("acme"));
            Reply complete = f.post("complete", Map.of("ticket", f.callback(login), "first", "Alice"), login.cookie);
            f.sessionCookie = complete.headers.get("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
            f.origin = "https://attacker.test";
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(400);
            assertThat(f.post("logout", Map.of(), null).status).isEqualTo(400);
            f.origin = "https://orion.test";
            assertThat(f.post("refresh", Map.of("organization", "default", "userId", "alice"), null).status)
                    .isEqualTo(401);
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(200);
            f.acl.updatePrimaryConfiguration(f.desired.current().revision().orElseThrow(), document -> {
                List<OrionDocument.Organization> organizations = new ArrayList<>();
                for (OrionDocument.Organization org : document.organizations()) {
                    List<AccessControl.User> users = new ArrayList<>();
                    for (AccessControl.User user : org.users()) {
                        if (!removed.equals("user")) {
                            users.add(new AccessControl.User(user.getId(), user.getFirst(), user.getLast(),
                                    user.getEmail(), removed.equals("binding") ? List.of() : user.getCredentials(),
                                    user.getRoles(), user.getGrants()));
                        }
                    }
                    organizations.add(new OrionDocument.Organization(org.id(), org.displayName(), users,
                            org.grants(), org.roles(), org.teams(), org.secrets(),
                            removed.equals("provider") ? List.of() : org.oidcProviders(), org.invitations()));
                }
                return new OrionDocument(document.system(), organizations);
            }, new AccessControlSaveRequest("remove account", null));
            assertThat(f.post("refresh", Map.of(), null).status).isEqualTo(401);
        }
    }

    @Test
    void administratorInvitesIntoAnyOrganizationAndBrowserCreatesAnIsolatedPersistedUser() throws Exception {
        try (Fixture f = new Fixture()) {
            Reply listing = f.request("GET", "/api/admin/invitations", Map.of(), Map.of(), null, f.admin);
            assertThat(listing.json.path("organizations")).hasSize(2);
            String invitation = f.invite("acme");
            assertThat(new String(f.storage.snapshot.files().get("orion.xml"), StandardCharsets.UTF_8))
                    .doesNotContain(invitation);
            f.acl.reload("restart before accepting invitation");
            Login login = f.start("acme", invitation);
            String ticket = f.callback(login);
            Reply profile = f.post("profile", Map.of("ticket", ticket), login.cookie);
            assertThat(profile.json.path("email").asText()).isEqualTo("alice@example.test");
            assertThat(profile.json.path("setup").asBoolean()).isTrue();
            assertThat(f.post("complete", Map.of("ticket", ticket, "first", "Alice", "last", "Smith"),
                    "wrong-browser").status).isEqualTo(400);
            Reply complete = f.post("complete", Map.of("ticket", ticket, "first", "Alice", "last", "Smith"),
                    login.cookie);
            assertThat(complete.status).isEqualTo(200);
            String token = complete.json.path("token").asText();
            TokenAuthenticationResult.Success authenticated = (TokenAuthenticationResult.Success)
                    f.acl.verifyToken(token.getBytes(StandardCharsets.UTF_8));
            UserIdentity identity = authenticated.userIdentity();
            assertThat(identity.getOrganizationId()).contains(new OrganizationId("acme"));
            SecurityContext context = SecurityContext.createContext().withUserIdentity(identity);
            assertThat(ApplicationAccessRules.admin().evaluate(context,
                    ApplicationAdminResource.applicationAdmin()).allowed()).isFalse();
            assertThat(RepositoryAccessRules.read().evaluate(context,
                    RepositoryResource.of("acme/team/repository")).allowed()).isTrue();
            assertThat(RepositoryAccessRules.read().evaluate(context,
                    RepositoryResource.of("default/team/repository")).allowed()).isFalse();
            assertThat(RepositoryAccessRules.write().evaluate(context,
                    RepositoryResource.of("acme/team/repository")).allowed()).isFalse();
            assertThat(f.request("POST", "/api/admin/invitations", Map.of("organization", "default",
                    "email", "outsider@example.test"), Map.of(), null, context).status).isEqualTo(403);
            assertThat(f.request("GET", "/api/admin/invitations", Map.of(), Map.of(), null, context).status)
                    .isEqualTo(403);
            assertThat(f.accounts.organization(new OrganizationId("default")).users()).isEmpty();
            assertThat(f.accounts.organization(new OrganizationId("acme")).users()).singleElement()
                    .satisfies(user -> assertThat(user.getFirst()).isEqualTo("Alice"));
            assertThat(f.accounts.organization(new OrganizationId("acme")).invitations()).isEmpty();
            assertThat(f.post("complete", Map.of("ticket", ticket, "first", "Replay"), login.cookie).status)
                    .isEqualTo(400);
            assertThatThrownBy(() -> f.accounts.invitation(new OrganizationId("acme"), invitation))
                    .isInstanceOf(IllegalArgumentException.class);
            f.acl.reload("restart after account creation");
            assertThat(f.acl.verifyToken(token.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Success.class);
            Login returning = f.start("acme", "");
            String returningTicket = f.callback(returning);
            assertThat(f.post("profile", Map.of("ticket", returningTicket), returning.cookie)
                    .json.path("setup").asBoolean()).isFalse();
            assertThat(f.post("complete", Map.of("ticket", returningTicket), returning.cookie).status).isEqualTo(200);
            f.acl.updatePrimaryConfiguration(f.desired.current().revision().orElseThrow(), document ->
                    new OrionDocument(document.system(), List.of(f.accounts.organization(new OrganizationId("default")))),
                    new AccessControlSaveRequest("remove acme", null));
            assertThat(f.acl.verifyToken(token.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);
        }
    }

    @Test
    void rejectsWrongEmailUnverifiedClaimsAndCrossBrowserOrReplayedCallbacks() throws Exception {
        try (Fixture f = new Fixture()) {
            String invitation = f.invite("acme");
            Login login = f.start("acme", invitation);
            assertThat(f.callbackReply(login, "another-browser").status).isEqualTo(400);
            f.email = "mallory@example.test";
            assertThat(f.callbackReply(login, login.cookie).status).isEqualTo(400);
            assertThat(f.callbackReply(login, login.cookie).status).isEqualTo(400);
            f.email = "alice@example.test";
            f.emailVerified = false;
            login = f.start("acme", invitation);
            assertThat(f.callbackReply(login, login.cookie).status).isEqualTo(400);
            f.emailVerified = true;
            f.badNonce = true;
            login = f.start("acme", invitation);
            assertThat(f.callbackReply(login, login.cookie).status).isEqualTo(400);
            f.badNonce = false;
            f.forgedSignature = true;
            login = f.start("acme", invitation);
            assertThat(f.callbackReply(login, login.cookie).status).isEqualTo(400);
            f.forgedSignature = false;
            f.audience = "another-client";
            login = f.start("acme", invitation);
            assertThat(f.callbackReply(login, login.cookie).status).isEqualTo(400);
            assertThat(f.accounts.organization(new OrganizationId("acme")).users()).isEmpty();
            assertThat(f.accounts.invitation(new OrganizationId("acme"), invitation)).isNotNull();
        }
    }

    @Test
    void replacementExpiryAndStorageConflictsNeverCreateAnUninvitedAccount() throws Exception {
        try (Fixture f = new Fixture()) {
            String old = f.invite("acme");
            String token = f.invite("acme");
            OrganizationId id = new OrganizationId("acme");
            assertThatThrownBy(() -> f.accounts.invitation(id, old)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> f.accounts.invitation(new OrganizationId("default"), token))
                    .isInstanceOf(IllegalArgumentException.class);
            OidcProvider provider = f.accounts.organization(id).oidcProviders().getFirst();
            f.storage.conflict = true;
            assertThatThrownBy(() -> f.accounts.accept(id, token, "alice@example.test", provider, "subject", "A", ""))
                    .isInstanceOf(AccessControlConcurrentUpdateException.class);
            assertThat(f.accounts.organization(id).users()).isEmpty();
            assertThat(f.accounts.invitation(id, token)).isNotNull();
            f.storage.conflict = false;
            f.acl.updatePrimaryConfiguration(f.desired.current().revision().orElseThrow(), document -> {
                List<OrionDocument.Organization> organizations = new ArrayList<>();
                for (OrionDocument.Organization org : document.organizations()) {
                    List<OrganizationInvitation> expired = new ArrayList<>();
                    for (OrganizationInvitation invite : org.invitations()) {
                        expired.add(new OrganizationInvitation(invite.tokenHash(), invite.email(), 1));
                    }
                    organizations.add(new OrionDocument.Organization(org.id(), org.displayName(), org.users(),
                            org.grants(), org.roles(), org.teams(), org.secrets(), org.oidcProviders(), expired));
                }
                return new OrionDocument(document.system(), organizations);
            }, new AccessControlSaveRequest("expire fixture invitation", null));
            assertThatThrownBy(() -> f.accounts.accept(id, token, "alice@example.test", provider, "subject", "A", ""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(f.accounts.organization(id).users()).isEmpty();
        }
    }

    @Test
    void theSameExternalAccountRequiresSeparateInvitationsForSeparateOrganizations() throws Exception {
        try (Fixture f = new Fixture()) {
            Set<String> users = new HashSet<>();
            for (String organization : List.of("default", "acme")) {
                Login login = f.start(organization, f.invite(organization));
                String ticket = f.callback(login);
                Reply response = f.post("complete", Map.of("ticket", ticket, "first", "Alice"), login.cookie);
                assertThat(response.status).isEqualTo(200);
                TokenAuthenticationResult.Success authenticated = (TokenAuthenticationResult.Success)
                        f.acl.verifyToken(response.json.path("token").asText().getBytes(StandardCharsets.UTF_8));
                assertThat(authenticated.userIdentity().getOrganizationId()).contains(new OrganizationId(organization));
                users.add(authenticated.userIdentity().getUserId());
            }
            assertThat(users).hasSize(2);
        }
    }

    @Test
    void adminSavesEncryptedProviderRotatesSecretAndRejectsStaleOrUnauthorizedEdits() throws Exception {
        try (Fixture f = new Fixture()) {
            String invite = f.invite("acme");
            Reply listing = f.request("GET", "/api/admin/oidc", Map.of(), Map.of(), null, f.admin);
            String revision = listing.json.path("revision").asText();
            Map<String, Object> input = new HashMap<>(Map.of("organization", "acme", "id", "google",
                    "issuer", "https://accounts.google.com", "clientId", "google-client",
                    "clientSecret", "private-google-secret", "revision", revision));
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null,
                    SecurityContext.createContext()).status).isEqualTo(403);
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(200);
            OrganizationId id = new OrganizationId("acme");
            OidcProvider provider = null;
            for (OidcProvider candidate : f.accounts.organization(id).oidcProviders()) {
                if (candidate.id().equals("google")) provider = candidate;
            }
            assertThat(provider).isNotNull();
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> f.desired.current().document(),
                    f.material.configurationCipher());
            assertThat(secrets.resolveOrganization(f.desired.current().document(), id, provider.secret()))
                    .isEqualTo("private-google-secret".toCharArray());
            assertThat(new String(f.storage.snapshot.files().get("orion.xml"), StandardCharsets.UTF_8))
                    .doesNotContain("private-google-secret");
            assertThat(f.accounts.invitation(id, invite)).isNotNull();
            assertThat(f.accounts.organization(new OrganizationId("default")).oidcProviders()).hasSize(1);
            listing = f.request("GET", "/api/admin/oidc", Map.of(), Map.of(), null, f.admin);
            assertThat(listing.json.toString()).doesNotContain("private-google-secret", "envelope", provider.secret());
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(409);
            input.put("revision", listing.json.path("revision").asText());
            input.put("clientSecret", "");
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(200);
            assertThat(secrets.resolveOrganization(f.desired.current().document(), id, provider.secret()))
                    .isEqualTo("private-google-secret".toCharArray());
            input.put("revision", f.desired.current().revision().orElseThrow());
            input.put("issuer", "https://other.example.test");
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(400);
            input.put("issuer", "https://accounts.google.com");
            input.put("clientSecret", "rotated-secret");
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(200);
            f.acl.reload("reload rotated provider");
            assertThat(secrets.resolveOrganization(f.desired.current().document(), id, provider.secret()))
                    .isEqualTo("rotated-secret".toCharArray());
        }
    }

    @Test
    void rotatingSharedProviderSecretLeavesOtherConsumersAndOrganizationsUntouched() throws Exception {
        try (Fixture f = new Fixture()) {
            f.acl.updatePrimaryConfiguration(f.desired.current().revision().orElseThrow(), document -> {
                List<OrionDocument.Organization> organizations = new ArrayList<>();
                for (OrionDocument.Organization org : document.organizations()) {
                    List<OidcProvider> providers = new ArrayList<>(org.oidcProviders());
                    providers.add(new OidcProvider("second", f.issuer, "second-client", "oidc",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0));
                    organizations.add(new OrionDocument.Organization(org.id(), org.displayName(), org.users(),
                            org.grants(), org.roles(), org.teams(), org.secrets(), providers, org.invitations()));
                }
                return new OrionDocument(document.system(), organizations);
            }, new AccessControlSaveRequest("share fixture secret", null));
            Map<String, Object> input = Map.of("organization", "acme", "id", "corporate",
                    "issuer", f.issuer.toString(), "clientId", "client", "clientSecret", "new-secret",
                    "revision", f.desired.current().revision().orElseThrow());
            SecurityContext scoped = SecurityContext.createContext().withUserIdentity(new InternalUserImpl("root",
                    new OrganizationId("acme"), () -> f.desired.current().document()));
            assertThat(f.request("GET", "/api/admin/oidc", Map.of(), Map.of(), null, scoped).status).isEqualTo(403);
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, scoped).status).isEqualTo(403);
            f.storage.conflict = true;
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(409);
            assertThat(f.accounts.organization(new OrganizationId("acme")).secrets()).hasSize(1);
            f.storage.conflict = false;
            assertThat(f.request("POST", "/api/admin/oidc", input, Map.of(), null, f.admin).status).isEqualTo(200);
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> f.desired.current().document(),
                    f.material.configurationCipher());
            for (String org : List.of("acme", "default")) {
                OrganizationId id = new OrganizationId(org);
                assertThat(secrets.resolveOrganization(f.desired.current().document(), id, "oidc"))
                        .isEqualTo("secret".toCharArray());
                for (OidcProvider provider : f.accounts.organization(id).oidcProviders()) {
                    String expected = org.equals("acme") && provider.id().equals("corporate")
                            ? "new-secret" : "secret";
                    assertThat(secrets.resolveOrganization(f.desired.current().document(), id, provider.secret()))
                            .isEqualTo(expected.toCharArray());
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final MemoryStorage storage = new MemoryStorage();
        final OrionDesiredState desired = new OrionDesiredState();
        final OrionKeyMaterial material;
        final OrionAccessControlServiceImpl acl;
        final OrganizationAccounts accounts;
        final HttpsServer provider;
        final SSLSocketFactory originalTls = HttpsURLConnection.getDefaultSSLSocketFactory();
        final RSAKey signingKey = new RSAKeyGenerator(2048).keyID("provider-key").generate();
        final SecurityContext admin = SecurityContext.createContext().withUserIdentity(new InternalUserImpl("root",
                List.of(new AccessControl.Grant("admin", List.of(
                        new AccessControl.GrantExpression(AccessControl.GrantKey.ADMIN, "true"))))));
        OrionHttpRouteServlet servlet;
        OrionAuthorizationFilter filter;
        final Path sessions = Files.createTempDirectory("orion-oidc-sessions-");
        ConfigurationSecrets secrets;
        String bearer;
        Long authenticationTime;
        Map<String, String> authorizationParameters;
        final MutableClock clock = new MutableClock();
        String sessionCookie;
        String origin = "https://orion.test";
        String email = "alice@example.test";
        String audience = "client";
        boolean emailVerified = true;
        boolean badNonce;
        boolean forgedSignature;
        String nonce;
        String challenge;
        final URI issuer;

        Fixture() throws Exception {
            KeyPair tlsKey = TestCertificateChain.keyPair();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("tls", tlsKey.getPrivate(), "password".toCharArray(),
                    new java.security.cert.Certificate[]{TestCertificateChain.selfSignedLeaf("localhost", tlsKey)});
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, "password".toCharArray());
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keyStore);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            provider = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
            provider.setHttpsConfigurator(new HttpsConfigurator(tls));
            issuer = URI.create("https://localhost:" + provider.getAddress().getPort());
            provider.createContext("/", exchange -> {
                try {
                    Object response = switch (exchange.getRequestURI().getPath()) {
                        case "/.well-known/openid-configuration" -> Map.of("issuer", issuer.toString(),
                                "authorization_endpoint", issuer + "/authorize", "token_endpoint", issuer + "/token",
                                "jwks_uri", issuer + "/keys");
                        case "/keys" -> new JWKSet(signingKey.toPublicJWK()).toJSONObject();
                        case "/token" -> {
                            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                                    .isEqualTo("Basic Y2xpZW50OnNlY3JldA==");
                            Map<String, String> form = query(new String(exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                            assertThat(form.get("redirect_uri")).isEqualTo("https://orion.test/api/auth/oidc/callback");
                            String actual = Base64.getUrlEncoder().withoutPadding().encodeToString(
                                    java.security.MessageDigest.getInstance("SHA-256").digest(
                                            form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                            assertThat(actual).isEqualTo(challenge);
                            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer.toString())
                                    .subject("external-alice").audience(audience).issueTime(new Date())
                                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                                    .claim("nonce", badNonce ? "wrong" : nonce).claim("email", email)
                                    .claim("email_verified", emailVerified).claim("given_name", "Alice")
                                    .claim("auth_time", authenticationTime).build();
                            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .keyID("provider-key").build(), claims);
                            jwt.sign(new RSASSASigner(forgedSignature ? new RSAKeyGenerator(2048).generate() : signingKey));
                            yield Map.of("id_token", jwt.serialize());
                        }
                        default -> throw new IllegalArgumentException("Unknown provider endpoint");
                    };
                    byte[] bytes = mapper.writeValueAsBytes(response);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable failure) {
                    exchange.sendResponseHeaders(500, -1);
                } finally { exchange.close(); }
            });
            provider.start();
            KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(new KeyMaterialAlias("signing"),
                    KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1),
                    KeyMaterialScope.cluster("test"));
            try (KeyMaterialOptions options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
                material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options,
                        new SigningMaterialSet(descriptor, List.of()), 2048, true);
            }
            secrets = new ConfigurationSecrets(() -> desired.current().document(),
                    material.configurationCipher());
            OrionHttpsConfiguration https = new OrionHttpsConfiguration(false, "localhost", 443,
                    URI.create("https://orion.test"), Optional.empty(), Optional.empty(),
                    OrionHttpsConfiguration.ClientAuthentication.DISABLED, List.of(), Optional.empty());
            OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("repository"), "",
                    OrionDocument.Repository.DEFAULT_BRANCH, RepositoryPolicy.safeDefaults(),
                    List.of(), List.of(), List.of(), List.of());
            OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                    List.of(repository));
            List<OrionDocument.Organization> organizations = new ArrayList<>();
            for (String id : List.of("default", "acme")) {
                organizations.add(new OrionDocument.Organization(new OrganizationId(id), id, List.of(), List.of(),
                        List.of(), List.of(team), List.of(new ConfigurationSecret("oidc", "placeholder")),
                        List.of(new OidcProvider("corporate", issuer, "client", "oidc",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0)), List.of()));
            }
            OrionDocument document = new OrionDocument(new OrionDocument.SystemConfiguration(new AccessControl(),
                    Optional.of(https), List.of(), List.of()), organizations);
            for (String id : List.of("default", "acme")) {
                document = secrets.replace(document, ConfigurationScope.organization(new OrganizationId(id)),
                        "oidc", "secret".toCharArray());
            }
            ByteArrayOutputStream xml = new ByteArrayOutputStream();
            OrionXml.write(document, xml);
            storage.snapshot = new AccessControlSnapshot(Map.of("orion.xml", xml.toByteArray()), Optional.of("0"));
            acl = new OrionAccessControlServiceImpl(storage, new OrionPasswordHashingService(), null,
                    OrionRuntimeOptions.defaults(), material.serverIdentity(), desired);
            acl.reload("fixture");
            accounts = new OrganizationAccounts(acl, desired);
            restart();
        }

        void restart() {
            OrionOidcRoute oidc = new OrionOidcRoute(accounts, acl, desired, secrets, mapper, clock,
                    sessions, material.configurationCipher());
            filter = new OrionAuthorizationFilter(acl, oidc);
            servlet = new OrionHttpRouteServlet(new OrionHttpRouteRegistry(Set.of(oidc,
                    new OrionAdminInvitationsRoute(accounts, desired, oidc, mapper),
                    new OrionAdminOidcRoute(desired, acl, secrets, mapper))), new OrionHttpResponseWriter(mapper));
        }

        String invite(String organization) throws Exception {
            Reply reply = request("POST", "/api/admin/invitations", Map.of("organization", organization,
                    "email", "Alice@example.test"), Map.of(), null, admin);
            assertThat(reply.status).isEqualTo(201);
            return query(URI.create(reply.json.path("url").asText()).getRawFragment()).get("invite");
        }

        Login start(String organization, String invitation) throws Exception {
            return start(organization, invitation, "corporate");
        }

        Login start(String organization, String invitation, String selectedProvider) throws Exception {
            Reply reply = post("start", Map.of("organization", organization, "provider", selectedProvider,
                    "invitation", invitation), null);
            assertThat(reply.status).isEqualTo(200);
            Map<String, String> parameters = query(URI.create(reply.json.path("url").asText()).getRawQuery());
            authorizationParameters = parameters;
            nonce = parameters.get("nonce");
            challenge = parameters.get("code_challenge");
            assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
            String cookie = reply.headers.get("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
            return new Login(parameters.get("state"), cookie);
        }

        void saveProvider(String id, long idle, long reauthentication) throws Exception {
            Reply result = request("POST", "/api/admin/oidc", Map.of("organization", "acme", "id", id,
                    "issuer", issuer.toString(), "clientId", "client", "clientSecret", "secret",
                    "revision", desired.current().revision().orElseThrow(), "idleTimeoutSeconds", idle,
                    "reauthenticationTimeoutSeconds", reauthentication), Map.of(), null, admin);
            assertThat(result.status).isEqualTo(200);
        }

        Reply signIn(String selectedProvider, String invitation) throws Exception {
            Login login = start("acme", invitation, selectedProvider);
            Reply result = post("complete", Map.of("ticket", callback(login), "first", "Alice"), login.cookie);
            assertThat(result.status).isEqualTo(200);
            sessionCookie = result.headers.get("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
            return result;
        }

        Reply activity(String method, String path, String token) throws Exception {
            bearer = token;
            try {
                return request(method, path, Map.of(), Map.of(), null, SecurityContext.createContext());
            } finally {
                bearer = null;
            }
        }

        Reply callbackReply(Login login, String cookie) throws Exception {
            return request("GET", "/api/auth/oidc/callback", Map.of(),
                    Map.of("state", login.state, "code", "authorization-code"), cookie, SecurityContext.createContext());
        }

        String callback(Login login) throws Exception {
            Reply reply = callbackReply(login, login.cookie);
            assertThat(reply.status).isEqualTo(303);
            assertThat(callbackReply(login, login.cookie).status).isEqualTo(400);
            return query(URI.create(reply.headers.get("Location")).getRawFragment()).get("onboarding");
        }

        Reply post(String path, Map<String, Object> body, String cookie) throws Exception {
            return request("POST", "/api/auth/oidc/" + path, body, Map.of(), cookie, SecurityContext.createContext());
        }

        Reply request(String method, String path, Map<String, Object> body, Map<String, String> parameters,
                String cookie, SecurityContext context) throws Exception {
            byte[] bytes = mapper.writeValueAsBytes(body);
            SecurityContext[] currentContext = {context};
            HttpServletRequest request = stub(HttpServletRequest.class, (proxy, called, args) -> switch (called.getName()) {
                case "getMethod" -> method;
                case "getPathInfo" -> path;
                case "getAttribute" -> currentContext[0];
                case "setAttribute" -> { currentContext[0] = (SecurityContext) args[1]; yield null; }
                case "toString" -> "OIDC test request";
                case "getRemoteAddr" -> "127.0.0.1";
                case "getContentType" -> "application/json";
                case "getHeader" -> switch ((String) args[0]) {
                    case "Origin" -> origin;
                    case "Authorization" -> bearer == null ? null : "Bearer " + bearer;
                    default -> null;
                };
                case "getParameter" -> parameters.get(args[0]);
                case "getCookies" -> {
                    List<Cookie> cookies = new ArrayList<>();
                    if (cookie != null) cookies.add(new Cookie("__Host-orion-oidc", cookie));
                    if (sessionCookie != null) cookies.add(new Cookie("__Host-orion-session", sessionCookie));
                    yield cookies.toArray(Cookie[]::new);
                }
                case "getInputStream" -> new Body(bytes);
                default -> throw new UnsupportedOperationException(called.toString());
            });
            int[] status = {0};
            Map<String, String> headers = new HashMap<>();
            StringWriter output = new StringWriter();
            HttpServletResponse response = stub(HttpServletResponse.class, (proxy, called, args) -> switch (called.getName()) {
                case "sendError", "setStatus" -> { status[0] = (int) args[0]; yield null; }
                case "setContentType" -> { headers.put("Content-Type", (String) args[0]); yield null; }
                case "setHeader", "addHeader" -> { headers.put((String) args[0], (String) args[1]); yield null; }
                case "getWriter" -> new PrintWriter(output);
                default -> throw new UnsupportedOperationException(called.toString());
            });
            if (bearer == null) {
                servlet.service(request, response);
            } else {
                filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> response.setStatus(200));
            }
            String value = output.toString();
            JsonNode json = value.startsWith("{") ? mapper.readTree(value) : mapper.nullNode();
            return new Reply(status[0], json, headers);
        }

        @Override public void close() {
            provider.stop(0);
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTls);
            material.close();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(sessions)) {
                for (Path file : files) Files.delete(file);
                Files.delete(sessions);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    private static Map<String, String> query(String value) {
        Map<String, String> result = new HashMap<>();
        for (String pair : value.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static final class MemoryStorage implements AccessControlStorage {
        AccessControlSnapshot snapshot;
        int saves;
        boolean conflict;
        @Override public Result<AccessControlSnapshot> load() { return new Result.Success<>(snapshot); }
        @Override public String primaryPath() { return "orion.xml"; }
        @Override public void save(AccessControlSnapshot next, AccessControlSaveRequest request) {
            if (conflict || !snapshot.version().equals(next.version())) {
                throw new AccessControlConcurrentUpdateException("configuration conflict", null);
            }
            snapshot = new AccessControlSnapshot(next.files(), Optional.of(Integer.toString(++saves)));
        }
    }
    private static final class Body extends ServletInputStream {
        final ByteArrayInputStream input;
        Body(byte[] bytes) { input = new ByteArrayInputStream(bytes); }
        @Override public int read() { return input.read(); }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { }
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private record Login(String state, String cookie) { }
    private record Reply(int status, JsonNode json, Map<String, String> headers) { }
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
