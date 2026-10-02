package pro.deta.orion.transport.http;

import pro.deta.orion.config.ConfigurationFile;
import pro.deta.orion.config.OrionConfigurationEditor;

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
import pro.deta.orion.config.*;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.internal.UserEmail;
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
import pro.deta.orion.schema.acl.Grant;
import pro.deta.orion.schema.acl.GrantExpression;
import pro.deta.orion.schema.config.OrionRuntimeOptions;
import pro.deta.orion.schema.orion.v2.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.schema.orion.v2.GitProxyBinding;
import pro.deta.orion.schema.orion.v2.RemoteAlias;
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
            assertThat(f.provider.syncObservation(f.desired.current().document().system().proxies().getFirst(),
                    f.desired.current().document().system())
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
            int saves = f.storage.saves;

            assertThat(f.answer(pending, "0").status).isEqualTo(204);
            assertThat(f.answer(pending, "0").status).isEqualTo(404);
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.work).hasSize(1);
            f.work.remove().run();

            assertThat(f.storage.saves).isEqualTo(saves + 1);
            f.editor.reload("verify host key persistence");
            var binding = f.desired.current().document().system().proxies().getFirst();
            assertThat(binding.knownHosts(f.desired.current().document().system())).containsExactlyInAnyOrder(previous, f.hostKey());
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.post(f.command("retry", "cluster", null, null)).json.at("/alias/status").asText())
                    .isEqualTo("success");
            assertThat(f.storage.saves).isEqualTo(saves + 1);
            assertThat(f.provider.syncObservation(binding,
                    f.desired.current().document().system()).status().name()).isEqualTo("SUCCESS");
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
            f.editor.edit(f.desired.current().revision().orElseThrow()).update(document -> document).apply("concurrent update", null);
            int saves = f.storage.saves;
            f.work.remove().run();
            assertThat(f.storage.saves).isEqualTo(saves);
            assertThat(f.sshAuthentications).hasValue(0);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system())).isEmpty();
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
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system())).isEmpty();
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
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system()))
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
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system())).isEmpty();
        }
    }

    @Test
    void savesKnownHostsAsKeysAndRetainsTrustOnlyForTheSameSshServer() throws Exception {
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
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system()))
                    .containsExactlyInAnyOrder(firstKey, secondKey);
            assertThat(f.post(f.command("update", "cluster", null, null)).status).isEqualTo(200);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system())).hasSize(2);
            Map<String, Object> update = f.command("update", "cluster", null, null);
            update.put("upstream", upstream + "-other");
            assertThat(f.post(update).status).isEqualTo(200);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system())).containsExactlyInAnyOrder(firstKey, secondKey);
            Map<String, Object> endpoint = f.command("update", "cluster", null, null);
            endpoint.put("upstream", upstream.replace("127.0.0.1", "localhost"));
            assertThat(f.post(endpoint).status).isEqualTo(200);
            assertThat(f.desired.current().document().system().proxies().getFirst().knownHosts(f.desired
                    .current().document().system())).isEmpty();
        }
    }

    @Test
    void savesAnEncryptedCredentialReplacesItExplicitlyAndAuditsWithoutSecrets() throws Exception {
        try (var f = new Fixture()) {
            Reply created = f.post(f.command("create", "credential", f.upstream(), "first-private-token"));
            assertThat(created.status).isEqualTo(201);
            assertThat(created.json.get("status").asText()).isEqualTo("saved");
            assertThat(created.json.at("/alias/status").asText()).isEqualTo("authentication-failed");
            String secret = f.desired.current().document().system().proxies().getFirst().secret(f.desired
                    .current().document().system()).orElseThrow();
            assertThat(f.secrets.resolveSystem(secret)).isEqualTo("first-private-token".toCharArray());
            assertThat(new String(f.storage.snapshot.content()))
                    .doesNotContain("first-private-token");
            Reply replaced = f.post(f.command("replace-credential", "credential", null, "second-private-token"));
            assertThat(replaced.status).isEqualTo(200);
            assertThat(f.secrets.resolveSystem(secret)).isEqualTo("second-private-token".toCharArray());
            assertThat(f.authorization).containsExactly("Bearer first-private-token", "Bearer second-private-token");
            f.editor.reload("test persisted reload");
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
            String original = f.desired.current().document().system().proxies().getFirst().secret(f.desired
                    .current().document().system()).orElseThrow();
            var metadata = f.command("update", "first", null, null);
            metadata.put("ref", "other");
            assertThat(f.post(metadata).status).isEqualTo(200);
            assertThat(f.secrets.resolveSystem(original)).isEqualTo("shared-private-token".toCharArray());
            var implicit = f.command("update", "first", null, "unapproved-replacement");
            assertThat(f.post(implicit).status).isEqualTo(400);
            f.editor.edit(f.desired.current().revision().orElseThrow()).update(document -> {
                var first = document.system().proxies().getFirst();
                var second = new GitProxyBinding(
                        new RemoteAlias("second"), first.source(), "third");
                return new OrionDocument(new OrionDocument.SystemConfiguration(document.system().accessControl(),
                        document.system().https(), document.system().secrets(), List.of(first, second),
                                document.system().connections()),
                        document.organizations());
            }).apply("share fixture credential", null);

            assertThat(f.post(f.command("replace-credential", "first", null, "new-private-token")).status)
                    .isEqualTo(200);
            var bindings = f.desired.current().document().system().proxies();
            assertThat(bindings.get(0).secret(f.desired.current().document().system())).isNotEqualTo(bindings
                    .get(1).secret(f.desired.current().document().system()));
            assertThat(f.secrets.resolveSystem(bindings.get(0).secret(f.desired.current().document().system()).orElseThrow()))
                    .isEqualTo("new-private-token".toCharArray());
            assertThat(f.secrets.resolveSystem(bindings.get(1).secret(f.desired.current().document().system()).orElseThrow()))
                    .isEqualTo("shared-private-token".toCharArray());
        }
    }

    @Test
    void refreshesTheReadRevisionAfterAnExternalConfigurationChange() throws Exception {
        try (var f = new Fixture()) {
            var old = f.command("create", "credential", f.upstream(), "private-token");
            f.storage.snapshot = new ConfigurationFile(f.storage.snapshot.content(), Optional.of("external"));
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
            String version = f.storage.snapshot.revision().orElseThrow();
            assertThat(f.post(first).status).isEqualTo(409);
            assertThat(f.storage.snapshot.revision()).contains(version);
            f.storage.conflict = true;
            Reply race = f.post(f.command("replace-credential", "credential", null, "new-private-token"));
            assertThat(race.status).isEqualTo(409);
            assertThat(race.json.get("status").asText()).isEqualTo("configuration-conflict");
            String id = f.desired.current().document().system().proxies().getFirst().secret(f.desired.current()
                    .document().system()).orElseThrow();
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
            var resolved = bootstrap.resolveProvisional("material", source, false);
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

    @Test
    void sharedSshConnectionPreservesTrustForPathEditsAndRejectsConnectionMutations() throws Exception {
        try (Fixture f = new Fixture()) {
            int unusedPort;
            try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
                unusedPort = socket.getLocalPort();
            }
            Map<String, Object> create = f.command("create", "cluster",
                    "ssh://git@127.0.0.1:" + unusedPort + "/repo", "ssh-private-password");
            create.put("credentialKind", "PASSWORD");
            create.put("knownHosts", Set.of(f.hostKey()));
            assertThat(f.post(create).status).isEqualTo(201);
            f.editor.edit(f.desired.current().revision().orElseThrow()).update(document -> {
                var first = document.system().proxies().getFirst();
                var source = (GitProxyBinding.Ssh) first.source();
                var second = new GitProxyBinding(
                        new RemoteAlias("second"),
                        new GitProxyBinding.Ssh(source.connection(), "/second"), "main");
                var system = document.system();
                return new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(), system.https(),
                        system.secrets(), List.of(first, second), system.connections()), document.organizations());
            }).apply("share SSH connection", UserEmail.EMPTY);
            OrionDocument original = f.desired.current().document();
            String upstream = original.system().proxies().getFirst().upstream(original.system()).toString();
            Map<String, Object> path = f.command("update", "cluster", upstream.replace("/repo", "/changed"), null);
            path.put("credentialKind", "PASSWORD");
            path.put("ref", "other");
            assertThat(f.post(path).status).isEqualTo(200);
            OrionDocument before = f.desired.current().document();
            assertThat(before.system().connections()).isEqualTo(original.system().connections());
            assertThat(before.system().proxies().getFirst().upstream(before.system()).getPath()).isEqualTo("/changed");
            assertThat(before.system().proxies().getFirst().ref()).isEqualTo("refs/heads/other");
            for (var binding : before.system().proxies()) {
                assertThat(binding.knownHosts(before.system())).containsExactly(f.hostKey());
            }
            Map<String, Object> endpoint = f.command("update", "cluster",
                    upstream.replace("127.0.0.1", "localhost"), null);
            endpoint.put("credentialKind", "PASSWORD");
            Reply moved = f.post(endpoint);
            assertThat(moved.status).isEqualTo(400);
            assertThat(moved.json.get("status").asText()).isEqualTo("shared-connection-requires-explicit-edit");
            assertThat(f.desired.current().document()).isEqualTo(before);
            Reply rejected = f.post(f.command("replace-credential", "cluster", null, "replacement-password"));
            assertThat(rejected.status).isEqualTo(400);
            assertThat(rejected.json.get("status").asText()).isEqualTo("shared-connection-requires-explicit-edit");
            assertThat(f.desired.current().document()).isEqualTo(before);
            String reference = before.system().proxies().getFirst().secret(before.system()).orElseThrow();
            assertThat(f.secrets.resolveSystem(reference)).isEqualTo("ssh-private-password".toCharArray());
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
        final OrionConfigurationEditor editor;
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
            editor = new OrionConfigurationEditor(storage,
                new pro.deta.orion.schema.config.OrionConfiguration(),
                material.configurationCipher(),
                material.configurationMaterial(),
                desired);
            acl = new OrionAccessControlServiceImpl(storage,
                new OrionPasswordHashingService(),
                OrionRuntimeOptions.defaults(),
                material.serverIdentity(),
                desired,
                editor,
                java.util.Optional.empty());
            acl.onStart();
            secrets = new ConfigurationSecrets(() -> desired.current().document(), material.configurationCipher());
            provider.connectionFailures(OrionRuntimeModule.connectionFailures(decisions),
                    OrionRuntimeModule.proxyHostKeyDecisions(desired, editor, audit::add));
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
            var route = new OrionAdminProxiesRoute(desired, provider, editor, secrets, sources, audit::add, mapper);
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
            List<Grant> grants = admin
                    ? List.of(new Grant("admin", List.of(
                            new GrantExpression(AccessControl.GrantKey.ADMIN, "true"))))
                    : List.of();
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

    private static final class MemoryStorage implements OrionConfigurationStorage {
        ConfigurationFile snapshot;
        int saves;
        boolean conflict;
        boolean failSave;

        MemoryStorage() throws IOException {
            var output = new ByteArrayOutputStream();
            OrionXml.write(OrionDocument.withAccessControl(new AccessControl()), output);
            snapshot = new ConfigurationFile(output.toByteArray(), Optional.of("0"));
        }
        @Override public Result<ConfigurationFile> load() { return new Result.Success<>(snapshot); }
        @Override public void save(ConfigurationFile next, String message, UserEmail author) {
            if (failSave) throw new IllegalStateException("storage unavailable");
            if (conflict || !snapshot.revision().equals(next.revision())) {
                throw new OrionConfigurationConcurrentUpdateException("configuration conflict", null);
            }
            snapshot = new ConfigurationFile(next.content(), Optional.of(Integer.toString(++saves)));
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
