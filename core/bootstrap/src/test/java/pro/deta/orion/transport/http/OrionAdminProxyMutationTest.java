package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.component.OrionRuntimeModule;
import pro.deta.orion.acl.storage.*;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.crypto.OrionPasswordHashingService;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.git.nativestorage.InMemoryNativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.BootstrapRepositorySources;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.keymaterial.*;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionAdminProxyMutationTest {
    private static final PrincipalAddress OPERATOR = PrincipalAddress.parse("system/operator");

    @Test
    void repositoryReadCreatesADecisionWithoutAnHttpRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThat(f.createSsh(Set.of()).status).isEqualTo(201);
            f.answer(f.decisions.list(OPERATOR).getFirst(), "1");
            f.work.remove().run();
            assertThat(f.decisions.list(OPERATOR)).isEmpty();
            String repository = f.desired.current().document().system().proxies().getFirst().publicRepositoryName();

            assertThatThrownBy(() -> f.provider.openForRead(repository)).isInstanceOf(IllegalStateException.class);

            assertThat(f.decisions.list(OPERATOR)).hasSize(1);
        }
    }

    @Test
    void persistentProxyActivationKeepsManagementAvailableForItsDecision() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThat(f.createSsh(Set.of()).status).isEqualTo(201);
            f.answer(f.decisions.list(OPERATOR).getFirst(), "1");
            f.work.remove().run();
            f.provider.activate(() -> f.desired.current().document(), f.secrets);
            assertThat(f.decisions.list(OPERATOR)).hasSize(1);
            assertThat(f.provider.syncObservation(f.desired.current().document().system().proxies().getFirst())
                    .status()).isEqualTo(ProxyAwareNativeGitRepositoryProvider.SyncStatus.UNAVAILABLE);
        }
    }

    @Test
    void retryWhileAnAnswerIsExecutingReusesTheRegisteredDecision() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThat(f.createSsh(Set.of()).status).isEqualTo(201);
            f.answer(f.decisions.list(OPERATOR).getFirst(), "0");
            assertThat(f.post(f.command("retry", "cluster", null, null)).status).isEqualTo(200);
            assertThat(f.work).hasSize(1);
        }
    }

    @Test
    void activationDoesNotHideAConnectionFailureWhenTheDecisionQueueIsFull() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThat(f.createSsh(Set.of()).status).isEqualTo(201);
            f.answer(f.decisions.list(OPERATOR).getFirst(), "1");
            f.work.remove().run();
            Decision unrelated = new Decision(UUID.randomUUID(), Optional.empty(), "Other operation", "",
                    List.of(new DecisionAction("Confirm", false, actor -> Result.of(null))));
            f.decisions.register(unrelated).valueOrFailure("fill queue");
            assertThatThrownBy(() -> f.provider.activate(() -> f.desired.current().document(), f.secrets))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(RejectedExecutionException.class);
            assertThat(f.decisions.list(OPERATOR)).containsExactly(unrelated.request());
        }
    }

    @Test
    void repeatedRetriesReuseThePendingConnectionDecision() throws Exception {
        try (Fixture f = new Fixture()) {
            assertThat(f.createSsh(Set.of()).status).isEqualTo(201);
            DecisionRequest first = f.decisions.list(OPERATOR).getFirst();
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThat(f.post(f.command("retry", "cluster", null, null)).status).isEqualTo(200);
                assertThat(f.decisions.list(OPERATOR)).containsExactly(first);
            }
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.work).isEmpty();
        }
    }

    @Test
    void approvedHostKeyIsSavedAndManualRetryPreservesClusterKeys(@TempDir Path root) throws Exception {
        Path bare = root.resolve("upstream.git");
        Path work = Files.createDirectory(root.resolve("work"));
        git(work, "init", "-b", "main");
        Files.writeString(work.resolve("file"), "content");
        git(work, "add", "file");
        git(work, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "seed");
        git(root, "clone", "--bare", work.toString(), bare.toString());
        try (Fixture f = new Fixture()) {
            f.ssh.setCommandFactory((channel, command) ->
                    new ProcessShellFactory("git-upload-pack", List.of("git-upload-pack", bare.toString()))
                            .createShell(channel));
            String previous = PublicKeyEntry.toString(new SimpleGeneratorHostKeyProvider()
                    .loadKeys(null).getFirst().getPublic());
            Reply created = f.createSsh(Set.of(previous));
            assertThat(created.status).isEqualTo(201);
            assertThat(created.json.at("/alias/status").asText()).isEqualTo("unavailable");
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.work).isEmpty();
            DecisionRequest pending = f.decisions.list(OPERATOR).getFirst();
            assertThat(pending.description()).contains(f.hostKey());
            Reply visible = f.request("/api/admin/decisions", "GET", Map.of(), true);
            assertThat(visible.json.get("decisions")).hasSize(1);
            byte[] secondary = f.storage.snapshot.files().get("secondary.xml");
            int saves = f.storage.saves;

            assertThat(f.answer(pending, "0").status).isEqualTo(204);
            assertThat(f.answer(pending, "0").status).isEqualTo(404);
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.work).hasSize(1);
            f.work.remove().run();

            assertThat(f.storage.saves).isEqualTo(saves + 1);
            assertThat(f.storage.snapshot.files().get("secondary.xml")).isEqualTo(secondary);
            f.acl.reload("verify host key persistence");
            var binding = f.desired.current().document().system().proxies().getFirst();
            assertThat(binding.knownHosts()).containsExactlyInAnyOrder(previous, f.hostKey());
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.post(f.command("retry", "cluster", null, null)).json.at("/alias/status").asText())
                    .isEqualTo("success");
            assertThat(f.storage.saves).isEqualTo(saves + 1);
            assertThat(f.provider.syncObservation(binding).status().name()).isEqualTo("SUCCESS");
            assertThat(f.sshAuthentications.get()).isPositive();
            assertThat(f.decisions.list(OPERATOR)).isEmpty();
            assertThat(f.audit).anySatisfy(record -> {
                assertThat(record.action()).isEqualTo("trust-host-key");
                assertThat(record.resultCode()).isEqualTo("saved");
                assertThat(record.userId()).isEqualTo(OPERATOR.toString());
            });
            assertThat(f.audit.toString()).doesNotContain("ssh-private-password", f.hostKey());
        }
    }

    @Test
    void rejectsTheKeyThenAllowsANewManualRetryToRequestApproval() throws Exception {
        try (Fixture f = new Fixture()) {
            f.createSsh(Set.of());
            int saves = f.storage.saves;
            DecisionRequest first = f.decisions.list(OPERATOR).getFirst();
            assertThat(f.answer(first, "1").status).isEqualTo(204);
            f.work.remove().run();
            assertThat(f.decisions.list(OPERATOR)).isEmpty();
            assertThat(f.work).isEmpty();
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.sshAuthentications).hasValue(0);

            assertThat(f.post(f.command("retry", "cluster", null, null)).status).isEqualTo(200);
            assertThat(f.decisions.list(OPERATOR)).hasSize(1);
            assertThat(f.decisions.list(OPERATOR).getFirst().id()).isNotEqualTo(first.id());
            f.decisions.close();
            assertThat(f.work).isEmpty();
        }
    }

    @Test
    void staleApprovalDoesNotSaveOrRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.createSsh(Set.of());
            f.answer(f.decisions.list(OPERATOR).getFirst(), "0");
            f.acl.updatePrimaryConfiguration(f.desired.current().revision().orElseThrow(), document -> document,
                    new AccessControlSaveRequest("concurrent update", null));
            int saves = f.storage.saves;
            f.work.remove().run();
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts()).isEmpty();
            assertThat(f.audit).extracting(CommandAuditRecord::resultCode).contains("configuration-conflict");
        }
    }

    @Test
    void failedSaveRemainsVisibleAndCanBeRetriedExplicitly() throws Exception {
        try (Fixture f = new Fixture()) {
            f.createSsh(Set.of());
            int saves = f.storage.saves;
            f.answer(f.decisions.list(OPERATOR).getFirst(), "0");
            f.storage.failSave = true;
            f.work.remove().run();
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts()).isEmpty();
            assertThat(f.audit).extracting(CommandAuditRecord::resultCode).contains("operation-failed");
            DecisionRequest failed = f.decisions.list(OPERATOR).getFirst();
            Reply visible = f.request("/api/admin/decisions", "GET", Map.of(), true);
            assertThat(visible.json.at("/decisions/0/state").asText()).isEqualTo("FAILED");
            assertThat(visible.json.at("/decisions/0/error").asText()).isEqualTo("Could not save SSH host key");
            assertThat(visible.json.at("/decisions/0/retryable").asBoolean()).isTrue();
            f.storage.failSave = false;
            assertThat(f.answer(failed, "retry").status).isEqualTo(204);
            assertThat(f.answer(failed, "retry").status).isEqualTo(404);
            f.work.remove().run();
            assertThat(f.decisions.list(OPERATOR)).isEmpty();
            assertThat(f.storage.saves).isEqualTo(saves + 1);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts())
                    .contains(f.hostKey());
            assertThat(f.sshAuthentications).hasValue(0);
        }
    }

    @Test
    void executorRejectionDoesNotSaveOrRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            f.createSsh(Set.of());
            int saves = f.storage.saves;
            f.rejectExecution = true;
            assertThat(f.answer(f.decisions.list(OPERATOR).getFirst(), "0").status).isEqualTo(204);
            assertThat(f.work).isEmpty();
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.sshAuthentications).hasValue(0);
            DecisionRequest failed = f.decisions.list(OPERATOR).getFirst();
            assertThat(failed.state()).isEqualTo(DecisionRequest.State.FAILED);
            assertThat(failed.error()).isEqualTo("Decision execution failed");
            assertThat(f.answer(failed, "close").status).isEqualTo(204);
            assertThat(f.decisions.list(OPERATOR)).isEmpty();
        }
    }

    @Test
    void fullDecisionRegistryReportsFailureWithoutTrustingTheKey() throws Exception {
        try (Fixture f = new Fixture()) {
            f.decisions.register(new Decision(UUID.randomUUID(),
                Optional.empty(), "occupied", "",
                List.of(new DecisionAction("Reject", false, actor -> Result.of(null)))));
            assertThat(f.createSsh(Set.of()).status).isEqualTo(503);
            assertThat(f.decisions.list(OPERATOR)).hasSize(1);
            assertThat(f.work).isEmpty();
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts()).isEmpty();
        }
    }

    @Test
    void savesKnownHostsAsKeysAndDoesNotTransferTrustToAnotherUrl() throws Exception {
        try (var f = new Fixture()) {
            int unusedPort;
            try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
                unusedPort = socket.getLocalPort();
            }
            String firstKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB";
            String secondKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIC";
            String upstream = "ssh://git@127.0.0.1:" + unusedPort + "/repo";
            Map<String, Object> create = f.command("create", "cluster", upstream, "password");
            create.put("credentialKind", "PASSWORD");
            create.put("knownHosts", List.of(firstKey, secondKey, firstKey));
            assertThat(f.post(create).status).isEqualTo(201);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts())
                    .containsExactlyInAnyOrder(firstKey, secondKey);
            assertThat(f.post(f.command("update", "cluster", null, null)).status).isEqualTo(200);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts()).hasSize(2);
            Map<String, Object> update = f.command("update", "cluster", null, null);
            update.put("upstream", upstream + "-other");
            assertThat(f.post(update).status).isEqualTo(200);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts()).isEmpty();
        }
    }

    @Test
    void savesAnEncryptedCredentialReplacesItExplicitlyAndAuditsWithoutSecrets() throws Exception {
        try (var f = new Fixture()) {
            byte[] secondary = f.storage.snapshot.files().get("secondary.xml");
            Reply created = f.post(f.command("create", "credential", f.upstream(), "first-private-token"));
            assertThat(created.status).isEqualTo(201);
            assertThat(created.json.get("status").asText()).isEqualTo("saved");
            assertThat(created.json.at("/alias/status").asText()).isEqualTo("authentication-failed");
            String secret = f.desired.current().document().system().proxies().getFirst().secret().orElseThrow();
            assertThat(f.secrets.resolveSystem(secret)).isEqualTo("first-private-token".toCharArray());
            assertThat(new String(f.storage.snapshot.files().get("orion.xml")))
                    .doesNotContain("first-private-token");
            assertThat(f.storage.snapshot.files().get("secondary.xml")).isEqualTo(secondary);

            Reply replaced = f.post(f.command("replace-credential", "credential", null, "second-private-token"));
            assertThat(replaced.status).isEqualTo(200);
            assertThat(f.secrets.resolveSystem(secret)).isEqualTo("second-private-token".toCharArray());
            assertThat(f.authorization).containsExactly("Bearer first-private-token", "Bearer second-private-token");
            f.acl.reload("test persisted reload");
            assertThat(f.secrets.resolveSystem(secret)).isEqualTo("second-private-token".toCharArray());
            assertThat(f.audit).hasSize(2);
            assertThat(f.audit.toString()).doesNotContain("first-private-token", "second-private-token", f.upstream());
            assertThat(replaced.json.toString()).doesNotContain("second-private-token", secret);
            assertThat(f.decisions.list(OPERATOR)).isEmpty();
        }
    }

    @Test
    void metadataEditsPreserveCredentialsAndSharedCredentialRotationIsIsolated() throws Exception {
        try (var f = new Fixture()) {
            assertThat(f.post(f.command("create", "first", f.upstream(), "shared-private-token")).status)
                    .isEqualTo(201);
            String original = f.desired.current().document().system().proxies().getFirst().secret().orElseThrow();
            var metadata = f.command("update", "first", null, null);
            metadata.put("ref", "other");
            assertThat(f.post(metadata).status).isEqualTo(200);
            assertThat(f.secrets.resolveSystem(original)).isEqualTo("shared-private-token".toCharArray());
            var implicit = f.command("update", "first", null, "unapproved-replacement");
            assertThat(f.post(implicit).status).isEqualTo(400);
            f.acl.updatePrimaryConfiguration(f.desired.current().revision().orElseThrow(), document -> {
                var first = document.system().proxies().getFirst();
                var second = new pro.deta.orion.schema.orion.GitProxyBinding(
                        new pro.deta.orion.schema.orion.RemoteAlias("second"), first.upstream(), "third",
                        first.credentialKind(), first.secret(), first.username(), first.knownHosts());
                return new OrionDocument(new OrionDocument.SystemConfiguration(document.system().accessControl(),
                        document.system().https(), document.system().secrets(), List.of(first, second)),
                        document.organizations());
            }, new AccessControlSaveRequest("share fixture credential", null));

            assertThat(f.post(f.command("replace-credential", "first", null, "new-private-token")).status)
                    .isEqualTo(200);
            var bindings = f.desired.current().document().system().proxies();
            assertThat(bindings.get(0).secret()).isNotEqualTo(bindings.get(1).secret());
            assertThat(f.secrets.resolveSystem(bindings.get(0).secret().orElseThrow()))
                    .isEqualTo("new-private-token".toCharArray());
            assertThat(f.secrets.resolveSystem(bindings.get(1).secret().orElseThrow()))
                    .isEqualTo("shared-private-token".toCharArray());
        }
    }

    @Test
    void refreshesTheReadRevisionAfterAnExternalConfigurationChange() throws Exception {
        try (var f = new Fixture()) {
            var old = f.command("create", "credential", f.upstream(), "private-token");
            f.storage.snapshot = new AccessControlSnapshot(f.storage.snapshot.files(), Optional.of("external"));
            assertThat(f.post(old).status).isEqualTo(409);
            Reply listing = f.request("GET", Map.of(), true);
            assertThat(listing.json.get("revision").asText()).isEqualTo("external");
            assertThat(f.post(f.command("create", "credential", f.upstream(), "private-token")).status)
                    .isEqualTo(201);
        }
    }

    @Test
    void staleRevisionAndStorageRaceCannotOverwriteConfiguration() throws Exception {
        try (var f = new Fixture()) {
            var first = f.command("create", "credential", f.upstream(), "private-token");
            assertThat(f.post(first).status).isEqualTo(201);
            String version = f.storage.snapshot.version().orElseThrow();
            assertThat(f.post(first).status).isEqualTo(409);
            assertThat(f.storage.snapshot.version()).contains(version);
            f.storage.conflict = true;
            Reply race = f.post(f.command("replace-credential", "credential", null, "new-private-token"));
            assertThat(race.status).isEqualTo(409);
            assertThat(race.json.get("status").asText()).isEqualTo("configuration-conflict");
            String id = f.desired.current().document().system().proxies().getFirst().secret().orElseThrow();
            assertThat(f.secrets.resolveSystem(id)).isEqualTo("private-token".toCharArray());
        }
    }

    @Test
    void readOnlyAndCrossScopeRequestsCannotMutateOrConsumeTheBody() throws Exception {
        try (var f = new Fixture()) {
            int saves = f.storage.saves;
            var request = f.command("create", "credential", f.upstream(), "private-token");
            Reply denied = f.request("POST", request, false);
            assertThat(denied.status).isEqualTo(403);
            assertThat(f.bodyReads).isZero();
            request.put("scope", "organization");
            assertThat(f.post(request).status).isEqualTo(400);
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.authorization).isEmpty();
        }
    }

    @Test
    void retryRecoversANativeFileProxyWithoutSavingConfiguration(@TempDir Path root) throws Exception {
        Path work = root.resolve("work");
        Path bare = root.resolve("upstream.git");
        Files.createDirectories(work);
        git(work, "init", "-b", "main");
        Files.writeString(work.resolve("file"), "content");
        git(work, "add", "file");
        git(work, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "seed");
        git(root, "clone", "--bare", work.toString(), bare.toString());
        try (var f = new Fixture()) {
            var create = f.command("create", "archive", bare.toUri().toString(), null);
            create.put("credentialKind", "NONE");
            Reply created = f.post(create);
            assertThat(created.status).isEqualTo(201);
            assertThat(created.json.at("/alias/status").asText()).isEqualTo("success");
            var source = new pro.deta.orion.schema.config.BootstrapSourceConfig();
            source.setLocation("git+" + bare.toUri());
            source.setRef("main");
            source.setPath("file");
            source.setAuth(Map.of());
            var bootstrap = new ProxyAwareNativeGitRepositoryProvider(new InMemoryNativeGitRepositoryProvider());
            var resolved = bootstrap.resolveProvisional("configuration", source, false);
            f.routes(new BootstrapRepositorySources(List.of(resolved)));
            var changeSource = f.command("update", "archive", null, null);
            changeSource.put("ref", "other");
            Reply protectedSource = f.post(changeSource);
            assertThat(protectedSource.status).isEqualTo(400);
            assertThat(protectedSource.json.get("status").asText()).isEqualTo("bootstrap-source-fixed");
            int saves = f.storage.saves;
            Path unavailable = root.resolve("temporarily-unavailable.git");
            Files.move(bare, unavailable);
            assertThat(f.post(f.command("retry", "archive", null, null)).json.at("/alias/status").asText())
                    .isEqualTo("unavailable");
            Files.move(unavailable, bare);
            assertThat(f.post(f.command("retry", "archive", null, null)).json.at("/alias/status").asText())
                    .isEqualTo("success");
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.provider.repositoryNames()).isEmpty();
        }
    }

    private static void git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as("native Git fixture command %s", command).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper mapper = new ObjectMapper();
        final OrionKeyMaterial material;
        final OrionDesiredState desired = new OrionDesiredState();
        final MemoryStorage storage = new MemoryStorage();
        final ProxyAwareNativeGitRepositoryProvider provider =
                new ProxyAwareNativeGitRepositoryProvider(new InMemoryNativeGitRepositoryProvider());
        final ConfigurationSecrets secrets;
        final OrionAccessControlServiceImpl acl;
        final HttpServer server;
        final List<String> authorization = new CopyOnWriteArrayList<>();
        final List<CommandAuditRecord> audit = new ArrayList<>();
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        boolean rejectExecution;
        final DecisionRegistry decisions = new DecisionRegistry(1, command -> {
            if (rejectExecution) throw new RejectedExecutionException("stopped");
            work.add(command);
        }, (actor, scope) -> actor.equals(OPERATOR));
        final SshServer ssh = SshServer.setUpDefaultServer();
        final SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider();
        final AtomicInteger sshAuthentications = new AtomicInteger();
        OrionHttpRouteServlet servlet;
        int bodyReads;

        Fixture() throws Exception {
            var signing = new KeyMaterialDescriptor(new KeyMaterialAlias("signing"),
                    KeyMaterialPurpose.SERVER_SIGNING, KeyMaterialAlgorithm.RSA, new KeyMaterialVersion(1),
                    KeyMaterialScope.cluster("test"));
            try (var options = KeyMaterialOptions.pkcs12("password".toCharArray())) {
                material = OrionKeyMaterial.open(new InMemoryKeyMaterialContentStore(), options,
                        new SigningMaterialSet(signing, List.of()), 2048, true);
            }
            acl = new OrionAccessControlServiceImpl(storage, new OrionPasswordHashingService(), null,
                    OrionRuntimeOptions.defaults(), material.serverIdentity(), desired);
            acl.reload("fixture");
            secrets = new ConfigurationSecrets(() -> desired.current().document(), material.configurationCipher());
            provider.connectionFailures(OrionRuntimeModule.connectionFailures(decisions),
                    OrionRuntimeModule.proxyHostKeyDecisions(desired, acl, audit::add));
            provider.activate(() -> desired.current().document(), secrets);
            routes(new BootstrapRepositorySources(List.of()));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/repository.git", exchange -> {
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });
            server.start();
        }

        void routes(BootstrapRepositorySources sources) {
            var route = new OrionAdminProxiesRoute(desired, provider, acl, secrets, sources, audit::add, mapper);
            servlet = new OrionHttpRouteServlet(new OrionHttpRouteRegistry(
                    Set.of(route, new OrionAdminDecisionsRoute(decisions, mapper))),
                    new OrionHttpResponseWriter(mapper));
        }

        Reply createSsh(Set<String> knownHosts) throws Exception {
            ssh.setHost("127.0.0.1");
            ssh.setPort(0);
            ssh.setKeyPairProvider(hostKeys);
            ssh.setPasswordAuthenticator((username, password, session) -> {
                sshAuthentications.incrementAndGet();
                return password.equals("ssh-private-password");
            });
            ssh.start();
            Map<String, Object> create = command("create", "cluster",
                    "ssh://git@127.0.0.1:" + ssh.getPort() + "/repo", "ssh-private-password");
            create.put("credentialKind", "PASSWORD");
            create.put("knownHosts", knownHosts);
            return post(create);
        }

        String hostKey() {
            return PublicKeyEntry.toString(hostKeys.loadKeys(null).getFirst().getPublic());
        }

        Reply answer(DecisionRequest pending, String action) throws Exception {
            return request("/api/admin/decisions", "POST",
                    Map.of("id", pending.id().toString(), "action", action), true);
        }

        String upstream() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/repository.git"; }

        Map<String, Object> command(String action, String alias, String upstream, String credential) {
            var command = new LinkedHashMap<String, Object>();
            command.put("action", action);
            command.put("scope", "system");
            command.put("alias", alias);
            command.put("revision", desired.current().revision().orElseThrow());
            if (upstream != null) {
                command.put("upstream", upstream);
                command.put("ref", "main");
                command.put("credentialKind", "TOKEN");
            }
            if (credential != null) command.put("credential", credential);
            return command;
        }

        Reply post(Map<String, Object> body) throws Exception { return request("POST", body, true); }

        Reply request(String method, Map<String, Object> body, boolean admin) throws Exception {
            return request("/api/admin/proxies", method, body, admin);
        }

        Reply request(String path, String method, Map<String, Object> body, boolean admin) throws Exception {
            var grants = admin ? List.of(new AccessControlDraft.Grant("admin", new ArrayList<>())
                    .addKey(AccessControl.GrantKey.ADMIN, "true").toAccessControl()) : List.<AccessControl.Grant>of();
            var context = SecurityContext.createContext().withUserIdentity(new InternalUserImpl("operator", grants));
            byte[] bytes = mapper.writeValueAsBytes(body);
            var request = stub(HttpServletRequest.class, (proxy, called, args) -> switch (called.getName()) {
                case "getMethod" -> method;
                case "getPathInfo" -> path;
                case "getContentType" -> "application/json";
                case "getRemoteAddr" -> "127.0.0.1";
                case "getAttribute" -> context;
                case "getInputStream" -> { bodyReads++; yield new Body(bytes); }
                default -> throw new UnsupportedOperationException(called.toString());
            });
            int[] status = {0};
            String[] contentType = {""};
            var output = new StringWriter();
            var response = stub(HttpServletResponse.class, (proxy, called, args) -> switch (called.getName()) {
                case "sendError", "setStatus" -> { status[0] = (int) args[0]; yield null; }
                case "setContentType" -> { contentType[0] = (String) args[0]; yield null; }
                case "setHeader" -> null;
                case "getWriter" -> new PrintWriter(output);
                default -> throw new UnsupportedOperationException(called.toString());
            });
            servlet.service(request, response);
            return new Reply(status[0], output.toString().isEmpty() ? mapper.nullNode()
                    : contentType[0].contains("json") ? mapper.readTree(output.toString())
                    : mapper.getNodeFactory().textNode(output.toString()));
        }

        @Override public void close() throws Exception {
            decisions.close();
            ssh.stop(true);
            server.stop(0);
            material.close();
        }
    }

    private static final class MemoryStorage implements AccessControlStorage {
        AccessControlSnapshot snapshot;
        int saves;
        boolean conflict;
        boolean failSave;

        MemoryStorage() throws IOException {
            var output = new ByteArrayOutputStream();
            OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), output);
            snapshot = new AccessControlSnapshot(Map.of("orion.xml", output.toByteArray(),
                    "secondary.xml", output.toByteArray()), Optional.of("0"));
        }
        @Override public Result<AccessControlSnapshot> load() { return new Result.Success<>(snapshot); }
        @Override public String primaryPath() { return "orion.xml"; }
        @Override public void save(AccessControlSnapshot next, AccessControlSaveRequest request) {
            if (failSave) throw new IllegalStateException("storage unavailable");
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
    private record Reply(int status, JsonNode json) { }
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
