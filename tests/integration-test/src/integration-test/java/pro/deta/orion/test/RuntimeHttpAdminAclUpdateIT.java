package pro.deta.orion.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.revwalk.RevCommit;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.acl.XmlService;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;

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
import static pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1;

class RuntimeHttpAdminAclUpdateIT {
    private static final String ACL_FILE = "orion.xml";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void configurationCommitsIdentifyTheAuthenticatedAdministrator() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("configuration-authors"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String rootToken = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"), 600);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(rootToken));
            AccessControlDraft draft = new XmlService().deserialize(new ByteArrayInputStream(
                    initial.body().getBytes(StandardCharsets.UTF_8))).toDraft();
            AccessControlDraft.User operator = AccessControlDraft.User.from(
                    draft.getUsers().getFirst().toAccessControl());
            operator.setId("operator");
            operator.setEmail("operator@example.test");
            operator.getCredentials().clear();
            operator.addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH);
            draft.getUsers().add(operator);
            byte[] xml = serialize(draft.toAccessControl());
            RuntimeHttpTestSupport.HttpResponse created = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(rootToken),
                    "application/xml", xml, initial.etag());
            assertThat(created.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertConfigurationCommitAuthor(orion, "root");

            String operatorToken = TestBearerTokens.issueToken(orion.httpUrl("/api/admin/token"),
                    "operator", TEST_PASSWORD.toCharArray(), 600);
            String bearer = TestBearerTokens.bearer(operatorToken);
            RuntimeHttpTestSupport.HttpResponse current = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            operator.setEmail("updated@example.test");
            xml = serialize(draft.toAccessControl());
            assertThat(RuntimeHttpTestSupport.request("POST", orion.httpUrl("/api/admin/acl"), bearer,
                    "application/xml", xml, current.etag()).status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertConfigurationCommitAuthor(orion, "operator");
            RuntimeHttpTestSupport.HttpResponse changed = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            assertThat(RuntimeHttpTestSupport.request("POST", orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(rootToken), "application/xml", xml, current.etag()).status())
                    .isEqualTo(HttpURLConnection.HTTP_CONFLICT);
            assertThat(RuntimeHttpTestSupport.request("GET", orion.httpUrl("/api/admin/acl"), bearer).etag())
                    .isEqualTo(changed.etag());
            assertConfigurationCommitAuthor(orion, "operator");

            Map<String, Object> command = Map.of("action", "create", "scope", "system", "alias", "archive",
                    "revision", changed.etag().replace("\"", ""),
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
    void oidcSecretIsEncryptedInGitAndPlaintextXmlIsRejectedBeforeCommit() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(
                tempDir.resolve("oidc-secret"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String token = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"), 600);
            String bearer = TestBearerTokens.bearer(token);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), bearer);
            OrionDocument base = new XmlService().deserializeDocument(new ByteArrayInputStream(
                    initial.body().getBytes(StandardCharsets.UTF_8)));
            OrionDocument.Organization acme = new OrionDocument.Organization(new OrganizationId("acme"), "Acme",
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            RuntimeHttpTestSupport.HttpResponse organization = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), bearer, "application/xml",
                    serializeDocument(new OrionDocument(base.system(), List.of(acme))), initial.etag());
            assertThat(organization.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);

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
            OrionDocument stored = new XmlService().deserializeDocument(new ByteArrayInputStream(persisted));
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
                    system.proxies()), stored.organizations());
            RuntimeHttpTestSupport.HttpResponse rejected = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), bearer, "application/xml",
                    serializeDocument(plaintext), current.etag());
            assertThat(rejected.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertThat(rejected.body()).doesNotContain("open-xml-secret");
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

            String token = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"), 600);
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
    void postAccessControlReloadsRuntimeAclAndSurvivesRestart() throws Exception {
        Path orionRoot = tempDir.resolve("orion-update");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);
        char[] rootPassword;

        RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration);
        try {
            rootPassword = orion.accessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create())
                    .clone();
            String token = TestBearerTokens.issueToken(
                    orion.httpUrl("/api/admin/token"),
                    "root",
                    rootPassword,
                    600);

            RuntimeHttpTestSupport.HttpResponse initialAcl = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(initialAcl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(userIds(initialAcl.body().getBytes(StandardCharsets.UTF_8))).containsExactly("root");

            byte[] updatedAcl = serialize(accessControlWithPasswordUser("http-updated-user"));
            RuntimeHttpTestSupport.HttpResponse update = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token),
                    "application/xml",
                    updatedAcl,
                    initialAcl.etag());

            assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertUserAuthenticates(orion, "http-updated-user");
            assertThat(orion.accessControlService().authenticateUser(
                    "root",
                    String.valueOf(rootPassword).getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Failure.class);
        } finally {
            orion.close();
        }

        try (RuntimeHttpTestSupport.StartedOrion restarted = RuntimeHttpTestSupport.start(configuration)) {
            assertUserAuthenticates(restarted, "http-updated-user");
            assertThat(userIds(readFileFromAclRepository(restarted))).containsExactly("http-updated-user");
        }
    }

    @Test
    void invalidAccessControlPostKeepsActiveAndStoredAclUnchanged() throws Exception {
        Path orionRoot = tempDir.resolve("orion-invalid-update");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            char[] rootPassword = orion.accessControlService()
                    .plainRootToken(PlainRootTokenAccessForTests.create())
                    .clone();
            String token = TestBearerTokens.issueToken(
                    orion.httpUrl("/api/admin/token"),
                    "root",
                    rootPassword,
                    600);
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
                    "<AccessControl><users>".getBytes(StandardCharsets.UTF_8),
                    activeBefore.etag());

            assertThat(update.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertThat(readFileFromAclRepository(orion)).containsExactly(storedBefore);
            RuntimeHttpTestSupport.HttpResponse activeAfter = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(activeAfter.body()).isEqualTo(activeBefore.body());
            assertThat(orion.accessControlService().authenticateUser(
                    "root",
                    String.valueOf(rootPassword).getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(AuthenticationResult.Success.class);
        }
    }

    @Test
    void staleAccessControlPostCannotReplaceANewerCommit() throws Exception {
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-stale"));
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String token = TestBearerTokens.issueRootToken(orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"), 600);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token));
            assertThat(initial.etag()).isNotBlank();

            RuntimeHttpTestSupport.HttpResponse missingRevision = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token),
                    "application/xml", withPasswordUser(initial.body(), "missing"));
            assertThat(missingRevision.status()).isEqualTo(428);

            RuntimeHttpTestSupport.HttpResponse first = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token),
                    "application/xml", withPasswordUser(initial.body(), "first"), initial.etag());
            assertThat(first.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            byte[] storedAfterFirst = readFileFromAclRepository(orion);

            RuntimeHttpTestSupport.HttpResponse stale = RuntimeHttpTestSupport.request(
                    "POST", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(token),
                    "application/xml", withPasswordUser(initial.body(), "second"), initial.etag());
            assertThat(stale.status()).isEqualTo(HttpURLConnection.HTTP_CONFLICT);
            assertThat(readFileFromAclRepository(orion)).containsExactly(storedAfterFirst);
            assertUserAuthenticates(orion, "first");
        }
    }

    private static AccessControl accessControlWithPasswordUser(String userId) {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH));
        return draft.toAccessControl();
    }

    private static byte[] withPasswordUser(String originalXml, String userId) throws IOException {
        AccessControlDraft draft = new XmlService().deserialize(
                new ByteArrayInputStream(originalXml.getBytes(StandardCharsets.UTF_8))).toDraft();
        draft.getUsers().add(ACLUtil.createUser(userId, userId + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH));
        return serialize(draft.toAccessControl());
    }

    private static byte[] serialize(AccessControl accessControl) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new XmlService().serialize(accessControl, output);
            return output.toByteArray();
        }
    }

    private static byte[] serializeDocument(OrionDocument document) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            new XmlService().serializeDocument(document, output);
            return output.toByteArray();
        }
    }

    private static List<String> userIds(byte[] content) throws IOException {
        AccessControl accessControl = new XmlService().deserialize(new ByteArrayInputStream(content));
        List<String> userIds = new ArrayList<>();
        for (AccessControl.User user : accessControl.getUsers()) {
            userIds.add(user.getId());
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
                .loadFiles(
                        orion.configuration().getBootstrap().getAccessControl().selectedRef(),
                        List.of(ACL_FILE))
                .files()
                .get(ACL_FILE).content();
    }
}
