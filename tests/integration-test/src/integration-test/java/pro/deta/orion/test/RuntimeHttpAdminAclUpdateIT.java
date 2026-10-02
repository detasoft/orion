package pro.deta.orion.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.revwalk.RevCommit;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.Credential;
import pro.deta.orion.schema.acl.User;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.schema.orion.v2.ConfigurationSecret;
import pro.deta.orion.schema.orion.v2.OrganizationId;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;

class RuntimeHttpAdminAclUpdateIT {
    private static final String ACL_FILE = "orion.xml";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void configurationRollbackCreatesANewCommitAndRevokesRemovedUserAccess() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("configuration-rollback"));
        String originalXml;
        String rollbackEtag;
        String rootToken;
        byte[] userToken;
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            rootToken = TestBearerTokens.issueRootToken(orion.accessControlService(), orion.component().configurationEditor(), 600);
            String bearer = TestBearerTokens.bearer(rootToken);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            originalXml = initial.body();
            byte[] originalContent = originalXml.getBytes(StandardCharsets.UTF_8);
            RuntimeHttpTestSupport.updateConfiguration(
                    orion, withPasswordUser(originalXml, "rollback-user"), initial.etag());
            RuntimeHttpTestSupport.HttpResponse changed = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            assertThat(changed.etag()).isNotEqualTo(initial.etag());
            assertUserAuthenticates(orion, "rollback-user");
            userToken = TestBearerTokens.issueToken(orion.httpUrl("/api/admin/token"),
                    "rollback-user", TEST_PASSWORD.toCharArray(), 600).getBytes(StandardCharsets.UTF_8);
            assertThat(orion.accessControlService().verifyToken(userToken))
                    .isInstanceOf(TokenAuthenticationResult.Success.class);

            assertThatThrownBy(() -> RuntimeHttpTestSupport.updateConfiguration(orion, originalContent, initial.etag()))
                    .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            RuntimeHttpTestSupport.HttpResponse afterConflict = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            assertThat(afterConflict.etag()).isEqualTo(changed.etag());
            assertThat(afterConflict.body()).isEqualTo(changed.body());
            assertUserAuthenticates(orion, "rollback-user");
            assertThat(orion.accessControlService().verifyToken(userToken))
                    .isInstanceOf(TokenAuthenticationResult.Success.class);

            RuntimeHttpTestSupport.updateConfiguration(orion, originalContent, changed.etag());
            RuntimeHttpTestSupport.HttpResponse rolledBack = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            rollbackEtag = rolledBack.etag();
            assertThat(rollbackEtag).isNotEqualTo(initial.etag()).isNotEqualTo(changed.etag());
            assertThat(rolledBack.body()).isEqualTo(originalXml);
            assertThat(new String(readFileFromAclRepository(orion), StandardCharsets.UTF_8))
                    .isEqualTo(originalXml);
            assertThat(orion.accessControlService().authenticateUser(
                    "rollback-user", TEST_PASSWORD.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(orion.accessControlService().verifyToken(userToken))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);

            NativeGitRepository repository = orion.repositoryProvider().openForRead("orion")
                    .valueOrFailure("configuration repository");
            RevCommit rollback = RevCommit.parse(repository.readObject(
                    new ObjectId(rollbackEtag.replace("\"", ""))).orElseThrow().data());
            assertThat(rollback.getParentCount()).isEqualTo(1);
            assertThat(rollback.getParent(0).name()).isEqualTo(changed.etag().replace("\"", ""));
            RevCommit previous = RevCommit.parse(repository.readObject(
                    new ObjectId(rollback.getParent(0).name())).orElseThrow().data());
            assertThat(previous.getParentCount()).isEqualTo(1);
            assertThat(previous.getParent(0).name()).isEqualTo(initial.etag().replace("\"", ""));
            RuntimeHttpTestSupport.HttpResponse status = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/configuration/status"), bearer);
            assertThat(status.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            JsonNode json = new ObjectMapper().readTree(status.body());
            assertThat(json.get("storedRevision").asText()).isEqualTo(rollbackEtag.replace("\"", ""));
            assertThat(json.get("activeRevision").asText()).isEqualTo(rollbackEtag.replace("\"", ""));
            assertThat(json.get("validation").asText()).isEqualTo("valid");
        }
        try (RuntimeHttpTestSupport.StartedOrion restarted = RuntimeHttpTestSupport.start(configuration)) {
            RuntimeHttpTestSupport.HttpResponse current = RuntimeHttpTestSupport.request(
                    "GET", restarted.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(rootToken));
            assertThat(current.etag()).isEqualTo(rollbackEtag);
            assertThat(current.body()).isEqualTo(originalXml);
            assertThat(restarted.accessControlService().authenticateUser(
                    "rollback-user", TEST_PASSWORD.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
            assertThat(restarted.accessControlService().verifyToken(userToken))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);
        }
    }

    @Test
    void configurationCommitsIdentifyTheAuthenticatedAdministrator() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("configuration-authors"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String rootToken = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.component().configurationEditor(), 600);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(rootToken));
            AccessControl acl = OrionXml.read(new ByteArrayInputStream(
                    initial.body().getBytes(StandardCharsets.UTF_8))).system().accessControl();
            User root = acl.users().getFirst();
            User operator = new User("operator", root.first(), root.last(),
                    "operator@example.test", List.of(new Credential(
                            AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH)),
                    root.roles(), root.grants());
            List<User> users = new ArrayList<>(acl.users());
            users.add(operator);
            byte[] xml = serialize(new AccessControl(users, acl.roles(), acl.grants()));
            RuntimeHttpTestSupport.updateConfiguration(orion, xml, initial.etag());

            String operatorToken = TestBearerTokens.issueToken(orion.httpUrl("/api/admin/token"),
                    "operator", TEST_PASSWORD.toCharArray(), 600);
            String bearer = TestBearerTokens.bearer(operatorToken);
            RuntimeHttpTestSupport.HttpResponse current = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            Map<String, Object> command = Map.of("action", "create", "scope", "system", "alias", "archive",
                    "revision", current.etag().replace("\"", ""),
                    "upstream", tempDir.resolve("offline.git").toUri().toString(),
                    "ref", "main", "credentialKind", "NONE");
            RuntimeHttpTestSupport.HttpResponse proxy = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/proxies"), bearer, "application/json",
                    new ObjectMapper().writeValueAsBytes(command));
            assertThat(proxy.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertConfigurationCommitAuthor(orion, "operator");
            RuntimeHttpTestSupport.HttpResponse proxyRevision = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            assertThat(RuntimeHttpTestSupport.request("POST", orion.httpUrl("/api/admin/proxies"),
                    TestBearerTokens.bearer(rootToken), "application/json",
                    new ObjectMapper().writeValueAsBytes(command)).status()).isEqualTo(HttpURLConnection.HTTP_CONFLICT);
            assertThat(RuntimeHttpTestSupport.request("GET", orion.httpUrl("/api/admin/acl"), bearer).etag())
                    .isEqualTo(proxyRevision.etag());
            assertConfigurationCommitAuthor(orion, "operator");
        }
    }

    private static void assertConfigurationCommitAuthor(
            RuntimeHttpTestSupport.StartedOrion orion, String expectedAuthor) {
        NativeGitRepository repository = orion.repositoryProvider().openForRead("orion")
                .valueOrFailure("configuration repository");
        String revision = repository.refs().get(
                orion.configuration().getBootstrap().getAccessControl().selectedRef());
        RevCommit commit = RevCommit.parse(repository.readObject(new ObjectId(revision)).orElseThrow().data());
        assertThat(commit.getAuthorIdent().getName()).isEqualTo(expectedAuthor);
    }

    @Test
    void oidcSecretIsEncryptedInGitAndPlaintextConfigurationIsRejectedBeforeCommit() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("oidc-secret"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String token = TestBearerTokens.issueRootToken(orion.accessControlService(), orion.component().configurationEditor(), 600);
            String bearer = TestBearerTokens.bearer(token);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            OrionDocument base = OrionXml.read(new ByteArrayInputStream(
                    initial.body().getBytes(StandardCharsets.UTF_8)));
            OrionDocument.Organization acme = new OrionDocument.Organization(new OrganizationId("acme"), "Acme",
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            RuntimeHttpTestSupport.updateConfiguration(orion,
                    serializeDocument(new OrionDocument(base.system(), List.of(acme))), initial.etag());

            RuntimeHttpTestSupport.HttpResponse listing = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/oidc"), bearer);
            String revision = new ObjectMapper().readTree(listing.body()).get("revision").asText();
            byte[] provider = new ObjectMapper().writeValueAsBytes(Map.of(
                    "organization", "acme", "id", "corporate", "issuer", "https://login.example.test",
                    "clientId", "orion", "clientSecret", "private-oidc-value", "revision", revision));
            RuntimeHttpTestSupport.HttpResponse saved = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/oidc"), bearer, "application/json", provider);
            assertThat(saved.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(saved.body()).doesNotContain("private-oidc-value");
            byte[] persisted = readFileFromAclRepository(orion);
            assertThat(new String(persisted, StandardCharsets.UTF_8)).doesNotContain("private-oidc-value");
            OrionDocument stored = OrionXml.read(new ByteArrayInputStream(persisted));
            assertThat(stored.organizations().getFirst().secrets()).hasSize(1);
            assertThat(stored.organizations().getFirst().secrets().getFirst().envelope()).isNotBlank();
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> stored,
                    orion.identity().material().configurationCipher());
            assertThat(secrets.resolveOrganization(stored, new OrganizationId("acme"),
                    stored.organizations().getFirst().oidcProviders().getFirst().secret()))
                    .isEqualTo("private-oidc-value".toCharArray());

            RuntimeHttpTestSupport.HttpResponse current = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            OrionDocument.SystemConfiguration system = stored.system();
            OrionDocument plaintext = new OrionDocument(new OrionDocument.SystemConfiguration(
                    system.accessControl(), system.https(),
                    List.of(new ConfigurationSecret("plain", "open-xml-secret")),
                    system.proxies(), system.connections()), stored.organizations());
            assertThatThrownBy(() -> RuntimeHttpTestSupport.updateConfiguration(
                    orion, serializeDocument(plaintext), current.etag()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(readFileFromAclRepository(orion)).containsExactly(persisted);
            assertThat(RuntimeHttpTestSupport.request("GET", orion.httpUrl("/api/admin/acl"), bearer).etag())
                    .isEqualTo(current.etag());
            assertThat(RuntimeHttpTestSupport.request("GET", orion.httpUrl("/api/admin/oidc"), bearer).body())
                    .doesNotContain("private-oidc-value", "open-xml-secret");
        }
    }

    @Test
    void configurationStatusIsAdminOnlyAndReportsTheActiveGitRevision() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-status"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            RuntimeHttpTestSupport.HttpResponse withoutToken = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/configuration/status"), null);
            assertThat(withoutToken.status()).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN);

            String token = TestBearerTokens.issueRootToken(orion.accessControlService(), orion.component().configurationEditor(), 600);
            RuntimeHttpTestSupport.HttpResponse acl = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token));
            RuntimeHttpTestSupport.HttpResponse status = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/configuration/status"), TestBearerTokens.bearer(token));
            assertThat(status.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            JsonNode json = new ObjectMapper().readTree(status.body());
            assertThat(json.get("storedRevision").asText()).isEqualTo(acl.etag().replace("\"", ""));
            assertThat(json.get("activeRevision").asText()).isEqualTo(acl.etag().replace("\"", ""));
            assertThat(json.get("validation").asText()).isEqualTo("valid");
            assertThat(json.size()).isEqualTo(3);
        }
    }

    @Test
    void configurationUpdateReloadsRuntimeAclAndSurvivesRestart() throws Exception {
        Path orionRoot = tempDir.resolve("orion-update");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration);
        try {
            String token = TestBearerTokens.issueRootToken(orion.accessControlService(), orion.component().configurationEditor(), 600);

            RuntimeHttpTestSupport.HttpResponse initialAcl = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(initialAcl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(userIds(initialAcl.body().getBytes(StandardCharsets.UTF_8))).containsExactly("root");

            byte[] updatedAcl = serialize(accessControlWithPasswordUser("http-updated-user"));
            RuntimeHttpTestSupport.updateConfiguration(orion, updatedAcl, initialAcl.etag());
            assertUserAuthenticates(orion, "http-updated-user");
            assertThat(orion.accessControlService().verifyToken(token.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Failure.class);
        } finally {
            orion.close();
        }

        try (RuntimeHttpTestSupport.StartedOrion restarted = RuntimeHttpTestSupport.start(configuration)) {
            assertUserAuthenticates(restarted, "http-updated-user");
            assertThat(userIds(readFileFromAclRepository(restarted))).containsExactly("http-updated-user");
        }
    }

    @Test
    void xmlUploadIsRejectedAndKeepsActiveAndStoredAclUnchanged() throws Exception {
        Path orionRoot = tempDir.resolve("orion-invalid-update");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String token = TestBearerTokens.issueRootToken(orion.accessControlService(), orion.component().configurationEditor(), 600);
            byte[] storedBefore = readFileFromAclRepository(orion);
            RuntimeHttpTestSupport.HttpResponse activeBefore = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));

            RuntimeHttpTestSupport.HttpResponse update = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token),
                    "application/xml",
                    "<orion schemaVersion=\"2\"><system>".getBytes(StandardCharsets.UTF_8),
                    activeBefore.etag());

            assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_BAD_METHOD);
            assertThat(readFileFromAclRepository(orion)).containsExactly(storedBefore);
            RuntimeHttpTestSupport.HttpResponse activeAfter = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(activeAfter.body()).isEqualTo(activeBefore.body());
            assertThat(activeAfter.status()).isEqualTo(HttpURLConnection.HTTP_OK);
        }
    }

    @Test
    void staleConfigurationUpdateCannotReplaceANewerCommit() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-stale"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String token = TestBearerTokens.issueRootToken(orion.accessControlService(), orion.component().configurationEditor(), 600);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token));
            assertThat(initial.etag()).isNotBlank();
            RuntimeHttpTestSupport.updateConfiguration(
                    orion, withPasswordUser(initial.body(), "first"), initial.etag());
            byte[] storedAfterFirst = readFileFromAclRepository(orion);
            assertThatThrownBy(() -> RuntimeHttpTestSupport.updateConfiguration(
                    orion, withPasswordUser(initial.body(), "second"), initial.etag()))
                    .isInstanceOf(OrionConfigurationConcurrentUpdateException.class);
            assertThat(readFileFromAclRepository(orion)).containsExactly(storedAfterFirst);
            assertUserAuthenticates(orion, "first");
        }
    }

    private static AccessControl accessControlWithPasswordUser(String userId) {
        return new AccessControl(List.of(passwordUser(userId)), List.of(), List.of());
    }

    private static byte[] withPasswordUser(String originalXml, String userId) throws IOException {
        AccessControl acl = OrionXml.read(
                new ByteArrayInputStream(originalXml.getBytes(StandardCharsets.UTF_8)))
                        .system().accessControl();
        List<User> users = new ArrayList<>(acl.users());
        users.add(passwordUser(userId));
        return serialize(new AccessControl(users, acl.roles(), acl.grants()));
    }

    private static User passwordUser(String userId) {
        return new User(userId, null, null, userId + "@example.test",
                List.of(new Credential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH)),
                List.of(), List.of());
    }

    private static byte[] serialize(AccessControl accessControl) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            OrionXml.write(OrionDocument.withAccessControl(accessControl), output);
            return output.toByteArray();
        }
    }

    private static byte[] serializeDocument(OrionDocument document) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            OrionXml.write(document, output);
            return output.toByteArray();
        }
    }

    private static List<String> userIds(byte[] content) throws IOException {
        AccessControl accessControl = OrionXml.read(new ByteArrayInputStream(content)).system().accessControl();
        List<String> userIds = new ArrayList<>();
        for (User user : accessControl.users()) {
            userIds.add(user.id());
        }
        return userIds;
    }

    private static void assertUserAuthenticates(RuntimeHttpTestSupport.StartedOrion orion, String userId) {
        assertThat(orion.accessControlService().authenticateUser(
                userId,
                TEST_PASSWORD.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AuthenticationResult.Success.class);
    }

    private static byte[] readFileFromAclRepository(RuntimeHttpTestSupport.StartedOrion orion) throws Exception {
        return orion.repositoryProvider()
                .openForRead("orion")
                .valueOrFailure("ACL repository should exist")
                .files().readBytes(orion.configuration().getBootstrap().getAccessControl().selectedRef(),
                        ACL_FILE);
    }
}
