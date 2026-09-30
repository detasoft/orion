package pro.deta.orion.test;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TransportHttp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.auth.TokenAuthenticationResult;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.git.nativestorage.FileNativeGitRepositoryProvider;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.ConfigurationSecret;
import pro.deta.orion.schema.orion.GrantAddress;
import pro.deta.orion.schema.orion.GrantId;
import pro.deta.orion.schema.orion.OidcProvider;
import pro.deta.orion.schema.orion.OrganizationId;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.RepositoryId;
import pro.deta.orion.schema.orion.RepositoryPolicy;
import pro.deta.orion.schema.orion.RoleId;
import pro.deta.orion.schema.orion.ScopedGrant;
import pro.deta.orion.schema.orion.ScopedRole;
import pro.deta.orion.schema.orion.TeamId;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeHttpGitRouteIT {
    private static final String BRANCH = "master";
    private static final String TEST_PASSWORD = "password";
    private static final String USERNAME = "http-git-user";
    private static final String TEST_PASSWORD_HASH = new OrionPasswordHashingService()
            .calculateHash(pro.deta.orion.crypto.PasswordHashingAlgorithm.SHA1, TEST_PASSWORD.toCharArray());

    @TempDir
    Path tempDir;

    @Test
    void sameNameOrganizationUsersHaveIsolatedGitAccessAndObserveRoleRevocation() throws Exception {
        Path orionRoot = tempDir.resolve("organization-http-git");
        Path repositoryRoot = orionRoot.resolve("repos");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration);
             Git acmeSource = initRepository(tempDir.resolve("acme-source"));
             Git otherSource = initRepository(tempDir.resolve("other-source"))) {
            String rootToken = TestBearerTokens.issueRootToken(orion.accessControlService(), 600);
            RuntimeHttpTestSupport.HttpResponse initial = RuntimeHttpTestSupport.request(
                    "GET", orion.httpUrl("/api/admin/acl"), TestBearerTokens.bearer(rootToken));
            OrionDocument base = OrionXml.read(new ByteArrayInputStream(
                    initial.body().getBytes(StandardCharsets.UTF_8)));
            OrionDocument document = new OrionDocument(base.system(), List.of(
                    gitOrganization("acme"), gitOrganization("other")));
            ConfigurationSecrets secrets = new ConfigurationSecrets(() -> base,
                    orion.identity().material().configurationCipher());
            for (String organization : List.of("acme", "other")) {
                document = secrets.replace(document, ConfigurationScope.organization(new OrganizationId(organization)),
                        "oidc", "test-client-secret".toCharArray());
            }
            assertThat(RuntimeHttpTestSupport.request("POST", orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(rootToken), "application/xml", serializeDocument(document),
                    initial.etag()).status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            String acmeToken = organizationToken(orion, "acme");
            String otherToken = organizationToken(orion, "other");
            TransportConfigCallback acmeAuthorization = bearerAuthorization(acmeToken);
            TransportConfigCallback otherAuthorization = bearerAuthorization(otherToken);
            String acmeUrl = orion.httpUrl("/r/acme/team/repo.git").toString();
            String otherUrl = orion.httpUrl("/r/other/team/repo.git").toString();
            ObjectId acmeCommit = createCommit(acmeSource, "README.md", "acme content\n", "acme seed");
            ObjectId otherCommit = createCommit(otherSource, "README.md", "other content\n", "other seed");
            assertSuccessfulPush(acmeSource, acmeUrl, acmeAuthorization);
            assertSuccessfulPush(otherSource, otherUrl, otherAuthorization);
            assertRepositoryContains(repositoryRoot, "acme/team/repo", acmeCommit, "README.md", "acme content\n");
            assertRepositoryContains(repositoryRoot, "other/team/repo", otherCommit, "README.md", "other content\n");
            for (String organization : List.of("acme", "other")) {
                String ownUrl = organization.equals("acme") ? acmeUrl : otherUrl;
                String foreignUrl = organization.equals("acme") ? otherUrl : acmeUrl;
                TransportConfigCallback authorization = organization.equals("acme")
                        ? acmeAuthorization : otherAuthorization;
                Git source = organization.equals("acme") ? acmeSource : otherSource;
                Path cloneDirectory = tempDir.resolve(organization + "-clone");
                try (Git clone = Git.cloneRepository().setURI(ownUrl).setDirectory(cloneDirectory.toFile())
                        .setBranch(BRANCH).setTransportConfigCallback(authorization).call()) {
                    assertThat(clone.getRepository().resolve("HEAD"))
                            .isEqualTo(organization.equals("acme") ? acmeCommit : otherCommit);
                    assertThat(Files.readString(cloneDirectory.resolve("README.md")))
                            .isEqualTo(organization + " content\n");
                }
                assertThatThrownBy(() -> Git.cloneRepository().setURI(foreignUrl)
                        .setDirectory(tempDir.resolve(organization + "-foreign-clone").toFile())
                        .setBranch(BRANCH).setTransportConfigCallback(authorization).call())
                        .isInstanceOf(TransportException.class);
                assertThatThrownBy(() -> source.push().setRemote(foreignUrl)
                        .setTransportConfigCallback(authorization)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH)).call())
                        .isInstanceOf(TransportException.class);
            }
            assertRepositoryRef(repositoryRoot, "acme/team/repo", BRANCH, acmeCommit);
            assertRepositoryRef(repositoryRoot, "other/team/repo", BRANCH, otherCommit);

            OrionDocument.Organization acme = document.organizations().getFirst();
            AccessControl.User assigned = acme.users().getFirst();
            AccessControl.User revoked = new AccessControl.User(assigned.getId(), assigned.getFirst(),
                    assigned.getLast(), assigned.getEmail(), assigned.getCredentials(), List.of(),
                    assigned.getGrants());
            OrionDocument updated = new OrionDocument(document.system(), List.of(new OrionDocument.Organization(
                    acme.id(), acme.displayName(), List.of(revoked), acme.grants(), acme.roles(), acme.teams(),
                    acme.secrets(), acme.oidcProviders(), acme.invitations(), acme.connections()), document.organizations().get(1)));
            assertThat(RuntimeHttpTestSupport.request("POST", orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(rootToken), "application/xml", serializeDocument(updated),
                    RuntimeHttpTestSupport.aclEtag(orion, rootToken)).status())
                    .isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertThat(orion.accessControlService().verifyToken(acmeToken.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(TokenAuthenticationResult.Success.class);
            assertThatThrownBy(() -> acmeSource.fetch().setRemote(acmeUrl)
                    .setTransportConfigCallback(acmeAuthorization)
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH)).call())
                    .isInstanceOf(TransportException.class);
            createCommit(acmeSource, "README.md", "revoked change\n", "revoked push");
            assertThatThrownBy(() -> acmeSource.push().setRemote(acmeUrl)
                    .setTransportConfigCallback(acmeAuthorization)
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH)).call())
                    .isInstanceOf(TransportException.class);
            assertRepositoryContains(repositoryRoot, "acme/team/repo", acmeCommit, "README.md", "acme content\n");
            otherSource.fetch().setRemote(otherUrl).setTransportConfigCallback(otherAuthorization)
                    .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH)).call();
            assertThat(otherSource.getRepository().resolve("refs/remotes/origin/" + BRANCH)).isEqualTo(otherCommit);
            ObjectId otherUpdated = createCommit(otherSource, "README.md", "other updated\n", "other update");
            assertSuccessfulPush(otherSource, otherUrl, otherAuthorization);
            assertRepositoryContains(repositoryRoot, "other/team/repo", otherUpdated, "README.md", "other updated\n");
        }
    }

    private static OrionDocument.Organization gitOrganization(String id) {
        String issuer = "https://login.example.test";
        AccessControl.User user = new AccessControl.User("alice", null, null, null,
                List.of(new AccessControl.Credential(AccessControl.CredentialType.OIDC_SUBJECT, issuer, "alice")),
                List.of(id + "/developer"), List.of());
        ScopedGrant grant = new ScopedGrant(new GrantId("write"), ScopedGrant.Effect.ALLOW, List.of(
                new AccessControl.GrantExpression(AccessControl.GrantKey.READ_WRITE, "true"),
                new AccessControl.GrantExpression(AccessControl.GrantKey.CREATE, "true")));
        ScopedRole role = new ScopedRole(new RoleId("developer"), List.of(),
                List.of(GrantAddress.parse(id + "/write")));
        OrionDocument.Repository repository = new OrionDocument.Repository(new RepositoryId("repo"), "",
                "refs/heads/" + BRANCH,
                RepositoryPolicy.safeDefaults(), List.of(), List.of(), List.of(), List.of(), java.util.Optional.empty());
        OrionDocument.Team team = new OrionDocument.Team(new TeamId("team"), "", List.of(), List.of(),
                List.of(repository));
        OidcProvider provider = new OidcProvider("oidc", URI.create(issuer), "client", "oidc",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0);
        return new OrionDocument.Organization(new OrganizationId(id), "", List.of(user), List.of(grant),
                List.of(role), List.of(team), List.of(new ConfigurationSecret("oidc", "placeholder")),
                List.of(provider), List.of(), List.of());
    }

    private static String organizationToken(RuntimeHttpTestSupport.StartedOrion orion, String organization) {
        TokenIssueResult issued = orion.accessControlService().issueOrganizationToken(
                new OrganizationId(organization), "alice", "https://login.example.test", "alice", 600);
        assertThat(issued).isInstanceOf(TokenIssueResult.Success.class);
        return ((TokenIssueResult.Success) issued).token();
    }

    private static byte[] serializeDocument(OrionDocument document) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            OrionXml.write(document, output);
            return output.toByteArray();
        }
    }

    private static void assertSuccessfulPush(Git source, String remote, TransportConfigCallback authorization)
            throws Exception {
        assertThat(source.push().setRemote(remote).setTransportConfigCallback(authorization)
                .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH)).call())
                .flatExtracting(PushResult::getRemoteUpdates).extracting(RemoteRefUpdate::getStatus)
                .containsExactly(RemoteRefUpdate.Status.OK);
    }

    @Test
    void jgitClientCanPushCloneAndFetchThroughHttpGitRoute() throws Exception {
        Path orionRoot = tempDir.resolve("orion-http-git");
        String repositoryName = "http-project";
        Path repositoryRoot = orionRoot.resolve("repos");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String remoteUrl = orion.httpUrl("/r/" + repositoryName + ".git").toString();
            Path sourceDirectory = tempDir.resolve("http-source");
            Path cloneDirectory = tempDir.resolve("http-clone");

            try (Git source = initRepository(sourceDirectory)) {
                ObjectId initialCommit = createCommit(source, "README.md", "hello over http\n", "initial http commit");
                assertThatThrownBy(() -> source.push()
                        .setRemote(remoteUrl)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call())
                        .isInstanceOf(TransportException.class);
                assertRepositoryDoesNotExist(repositoryRoot, repositoryName);

                String rootToken = TestBearerTokens.issueRootToken(
                        orion.accessControlService(),
                        600);
                RuntimeHttpTestSupport.HttpResponse updateAcl = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken),
                        "application/xml",
                        serialize(accessControlForHttpGitUser(repositoryName)),
                        RuntimeHttpTestSupport.aclEtag(orion, rootToken));
                assertThat(updateAcl.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);

                String userToken = TestBearerTokens.issueToken(
                        orion.httpUrl("/api/admin/token"),
                        USERNAME,
                        TEST_PASSWORD.toCharArray(),
                        600);
                TransportConfigCallback authorization = bearerAuthorization(userToken);

                Iterable<PushResult> pushResults = source.push()
                        .setRemote(remoteUrl)
                        .setTransportConfigCallback(authorization)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();

                assertThat(pushResults)
                        .flatExtracting(PushResult::getRemoteUpdates)
                        .extracting(RemoteRefUpdate::getStatus)
                        .containsExactly(RemoteRefUpdate.Status.OK);
                assertRepositoryContains(
                        repositoryRoot,
                        repositoryName,
                        initialCommit,
                        "README.md",
                        "hello over http\n");

                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(cloneDirectory.toFile())
                        .setBranch(BRANCH)
                        .setTransportConfigCallback(authorization)
                        .call()) {
                    assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("hello over http\n");

                    ObjectId updatedCommit = createCommit(source, "README.md", "updated over http\n", "update http commit");
                    source.push()
                            .setRemote(remoteUrl)
                            .setTransportConfigCallback(authorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                            .call();
                    assertRepositoryContains(
                            repositoryRoot,
                            repositoryName,
                            updatedCommit,
                            "README.md",
                            "updated over http\n");

                    clone.fetch()
                            .setRemote("origin")
                            .setTransportConfigCallback(authorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH))
                            .call();
                    assertThat(clone.getRepository().resolve("refs/remotes/origin/" + BRANCH)).isEqualTo(updatedCommit);
                }
            }
        }

        assertThat(new FileNativeGitRepositoryProvider(repositoryRoot).repositoryNames())
                .contains(repositoryName);
    }

    @Test
    void readOnlyBearerUserCanCloneButCannotPushOrCreateThroughHttpGitRoute() throws Exception {
        Path orionRoot = tempDir.resolve("orion-http-git-read-only");
        String repositoryName = "http-read-only-project";
        String createdRepositoryName = "http-read-only-created";
        Path repositoryRoot = orionRoot.resolve("repos");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String remoteUrl = orion.httpUrl("/r/" + repositoryName + ".git").toString();
            Path sourceDirectory = tempDir.resolve("http-read-only-source");
            Path cloneDirectory = tempDir.resolve("http-read-only-clone");

            try (Git source = initRepository(sourceDirectory)) {
                ObjectId initialCommit = createCommit(source, "README.md", "seeded for read-only http\n", "seed http commit");
                String rootToken = TestBearerTokens.issueRootToken(
                        orion.accessControlService(),
                        600);
                TransportConfigCallback rootAuthorization = bearerAuthorization(rootToken);

                Iterable<PushResult> seedPushResults = source.push()
                        .setRemote(remoteUrl)
                        .setTransportConfigCallback(rootAuthorization)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call();
                assertThat(seedPushResults)
                        .flatExtracting(PushResult::getRemoteUpdates)
                        .extracting(RemoteRefUpdate::getStatus)
                        .containsExactly(RemoteRefUpdate.Status.OK);
                assertRepositoryContains(
                        repositoryRoot,
                        repositoryName,
                        initialCommit,
                        "README.md",
                        "seeded for read-only http\n");

                RuntimeHttpTestSupport.HttpResponse updateAcl = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken),
                        "application/xml",
                        serialize(accessControlForHttpGitUser(repositoryName, true, false, false)),
                        RuntimeHttpTestSupport.aclEtag(orion, rootToken));
                assertThat(updateAcl.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);

                String userToken = TestBearerTokens.issueToken(
                        orion.httpUrl("/api/admin/token"),
                        USERNAME,
                        TEST_PASSWORD.toCharArray(),
                        600);
                TransportConfigCallback readOnlyAuthorization = bearerAuthorization(userToken);

                assertThatThrownBy(() -> source.push()
                        .setRemote(orion.httpUrl("/r/" + createdRepositoryName + ".git").toString())
                        .setTransportConfigCallback(readOnlyAuthorization)
                        .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                        .call())
                        .isInstanceOf(TransportException.class);
                assertRepositoryDoesNotExist(repositoryRoot, createdRepositoryName);

                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(cloneDirectory.toFile())
                        .setBranch(BRANCH)
                        .setTransportConfigCallback(readOnlyAuthorization)
                        .call()) {
                    assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("seeded for read-only http\n");

                    createCommit(clone, "README.md", "read-only update over http\n", "read-only update http commit");
                    assertThatThrownBy(() -> clone.push()
                            .setRemote("origin")
                            .setTransportConfigCallback(readOnlyAuthorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                            .call())
                            .isInstanceOf(TransportException.class);
                }

                assertRepositoryContains(
                        repositoryRoot,
                        repositoryName,
                        initialCommit,
                        "README.md",
                        "seeded for read-only http\n");
            }
        }
    }

    @Test
    void bearerUserIsLimitedToGrantedHttpGitBranchAndCannotForcePushWithoutForceGrant() throws Exception {
        Path orionRoot = tempDir.resolve("orion-http-git-branch");
        String repositoryName = "http-branch-project";
        String featureBranch = "feature";
        Path repositoryRoot = orionRoot.resolve("repos");
        OrionConfiguration configuration = RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot);

        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(configuration)) {
            String remoteUrl = orion.httpUrl("/r/" + repositoryName + ".git").toString();
            Path sourceDirectory = tempDir.resolve("http-branch-source");
            Path cloneDirectory = tempDir.resolve("http-branch-clone");
            Path forceDirectory = tempDir.resolve("http-branch-force-source");

            try (Git source = initRepository(sourceDirectory)) {
                ObjectId masterCommit = createCommit(source, "README.md", "master over http\n", "seed master");
                String rootToken = TestBearerTokens.issueRootToken(
                        orion.accessControlService(),
                        600);
                TransportConfigCallback rootAuthorization = bearerAuthorization(rootToken);

                assertPushStatus(
                        source.push()
                                .setRemote(remoteUrl)
                                .setTransportConfigCallback(rootAuthorization)
                                .setRefSpecs(new RefSpec("refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                                .call(),
                        RemoteRefUpdate.Status.OK);

                source.checkout()
                        .setCreateBranch(true)
                        .setName(featureBranch)
                        .call();
                ObjectId featureCommit = createCommit(source, "FEATURE.md", "feature over http\n", "seed feature");
                assertPushStatus(
                        source.push()
                                .setRemote(remoteUrl)
                                .setTransportConfigCallback(rootAuthorization)
                                .setRefSpecs(new RefSpec("refs/heads/" + featureBranch + ":refs/heads/" + featureBranch))
                                .call(),
                        RemoteRefUpdate.Status.OK);
                source.checkout().setName(BRANCH).call();

                assertRepositoryRef(repositoryRoot, repositoryName, BRANCH, masterCommit);
                assertRepositoryRef(repositoryRoot, repositoryName, featureBranch, featureCommit);

                RuntimeHttpTestSupport.HttpResponse updateAcl = RuntimeHttpTestSupport.request(
                        "POST",
                        orion.httpUrl("/api/admin/acl"),
                        TestBearerTokens.bearer(rootToken),
                        "application/xml",
                        serialize(accessControlForHttpGitUser(repositoryName, true, true, true, BRANCH, false)),
                        RuntimeHttpTestSupport.aclEtag(orion, rootToken));
                assertThat(updateAcl.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);

                String userToken = TestBearerTokens.issueToken(
                        orion.httpUrl("/api/admin/token"),
                        USERNAME,
                        TEST_PASSWORD.toCharArray(),
                        600);
                TransportConfigCallback branchAuthorization = bearerAuthorization(userToken);

                try (Git clone = Git.cloneRepository()
                        .setURI(remoteUrl)
                        .setDirectory(cloneDirectory.toFile())
                        .setBranchesToClone(List.of("refs/heads/" + BRANCH))
                        .setBranch(BRANCH)
                        .setTransportConfigCallback(branchAuthorization)
                        .call()) {
                    assertThat(Files.readString(cloneDirectory.resolve("README.md"))).isEqualTo("master over http\n");

                    assertThatThrownBy(() -> clone.fetch()
                            .setRemote("origin")
                            .setTransportConfigCallback(branchAuthorization)
                            .setRefSpecs(new RefSpec("refs/heads/" + featureBranch + ":refs/remotes/origin/" + featureBranch))
                            .call())
                            .isInstanceOf(TransportException.class);
                    assertThat(clone.getRepository().resolve("refs/remotes/origin/" + featureBranch)).isNull();
                }

                source.checkout().setName(featureBranch).call();
                createCommit(source, "FEATURE.md", "denied feature update over http\n", "denied feature update");
                assertPushStatus(
                        source.push()
                                .setRemote(remoteUrl)
                                .setTransportConfigCallback(branchAuthorization)
                                .setRefSpecs(new RefSpec("refs/heads/" + featureBranch + ":refs/heads/" + featureBranch))
                                .call(),
                        RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
                assertRepositoryRef(repositoryRoot, repositoryName, featureBranch, featureCommit);

                try (Git forceSource = initRepository(forceDirectory)) {
                    createCommit(forceSource, "README.md", "denied force over http\n", "denied force");
                    assertPushStatus(
                            forceSource.push()
                                    .setRemote(remoteUrl)
                                    .setTransportConfigCallback(branchAuthorization)
                                    .setRefSpecs(new RefSpec("+refs/heads/" + BRANCH + ":refs/heads/" + BRANCH))
                                    .call(),
                            RemoteRefUpdate.Status.REJECTED_OTHER_REASON);
                }
                assertRepositoryRef(repositoryRoot, repositoryName, BRANCH, masterCommit);
            }
        }
    }

    private static AccessControl accessControlForHttpGitUser(String repositoryName) {
        return accessControlForHttpGitUser(repositoryName, true, true, true);
    }

    private static AccessControl accessControlForHttpGitUser(
            String repositoryName,
            boolean read,
            boolean write,
            boolean create) {
        return accessControlForHttpGitUser(repositoryName, read, write, create, "*", false);
    }

    private static AccessControl accessControlForHttpGitUser(
            String repositoryName,
            boolean read,
            boolean write,
            boolean create,
            String branch,
            boolean force) {
        AccessControlDraft draft = ACLUtil.generateDefaultAccessControl(
                TEST_PASSWORD_HASH,
                AccessControl.CredentialType.SHA1).toDraft();
        AccessControlDraft.User user = ACLUtil.createUser(USERNAME, USERNAME + "@example.test")
                .addCredential(AccessControl.CredentialType.SHA1, TEST_PASSWORD_HASH);
        AccessControlDraft.Grant grant = user.addGrant("REPOSITORY_" + repositoryName)
                .addKey(AccessControl.GrantKey.REPOSITORY, repositoryName)
                .addKey(AccessControl.GrantKey.BRANCH, branch);
        if (read) {
            grant.addKey(AccessControl.GrantKey.READ, AccessControl.TRUE_STRING);
        }
        if (write) {
            grant.addKey(AccessControl.GrantKey.READ_WRITE, AccessControl.TRUE_STRING);
        }
        if (create) {
            grant.addKey(AccessControl.GrantKey.CREATE, AccessControl.TRUE_STRING);
        }
        if (force) {
            grant.addKey(AccessControl.GrantKey.FORCE, AccessControl.TRUE_STRING);
        }
        draft.getUsers().add(user);
        return draft.toAccessControl();
    }

    private static byte[] serialize(AccessControl accessControl) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            OrionXml.write(OrionDocument.withAccessControl(accessControl), output);
            return output.toByteArray();
        }
    }

    private static TransportConfigCallback bearerAuthorization(String token) {
        return transport -> {
            if (transport instanceof TransportHttp http) {
                http.setAdditionalHeaders(Map.of("Authorization", TestBearerTokens.bearer(token)));
            }
        };
    }

    private static Git initRepository(Path directory) throws Exception {
        Files.createDirectories(directory);
        Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch(BRANCH)
                .call();
        git.getRepository().getConfig().setString("user", null, "name", "HTTP Git Test");
        git.getRepository().getConfig().setString("user", null, "email", "http-git@example.test");
        git.getRepository().getConfig().save();
        return git;
    }

    private static ObjectId createCommit(Git git, String fileName, String content, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve(fileName), content);
        git.add().addFilepattern(fileName).call();
        return git.commit()
                .setAuthor("HTTP Git Test", "http-git@example.test")
                .setCommitter("HTTP Git Test", "http-git@example.test")
                .setMessage(message + " " + Instant.now())
                .call()
                .toObjectId();
    }

    private static void assertRepositoryContains(
            Path repositoryRoot,
            String repositoryName,
            ObjectId commitId,
            String fileName,
            String expectedContent) throws Exception {
        NativeGitRepository repository = nativeRepository(repositoryRoot, repositoryName);
        assertThat(repository.refs())
                .containsEntry("refs/heads/" + BRANCH, commitId.name());
        assertThat(repository.readObject(new pro.deta.orion.git.parser.v2.id.ObjectId(commitId.name())))
                .isPresent();
        var snapshot = Map.of(fileName, repository.files().readBytes(BRANCH, fileName));
        assertThat(new String(snapshot.get(fileName), StandardCharsets.UTF_8))
                .isEqualTo(expectedContent);
    }

    private static void assertRepositoryRef(
            Path repositoryRoot,
            String repositoryName,
            String branch,
            ObjectId commitId) {
        assertThat(nativeRepository(repositoryRoot, repositoryName).refs())
                .containsEntry("refs/heads/" + branch, commitId.name());
    }

    private static void assertRepositoryDoesNotExist(
            Path repositoryRoot,
            String repositoryName) {
        assertThat(new FileNativeGitRepositoryProvider(repositoryRoot).exists(repositoryName))
                .isFalse();
    }

    private static NativeGitRepository nativeRepository(
            Path repositoryRoot,
            String repositoryName) {
        return new FileNativeGitRepositoryProvider(repositoryRoot)
                .find(repositoryName)
                .valueOrFailure("repository");
    }

    private static void assertPushStatus(Iterable<PushResult> pushResults, RemoteRefUpdate.Status status) {
        assertThat(pushResults)
                .flatExtracting(PushResult::getRemoteUpdates)
                .extracting(RemoteRefUpdate::getStatus)
                .containsExactly(status);
    }

}
