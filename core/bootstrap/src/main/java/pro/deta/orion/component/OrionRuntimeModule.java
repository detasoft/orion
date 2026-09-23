package pro.deta.orion.component;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.slf4j.LoggerFactory;
import pro.deta.orion.command.audit.CommandAuditRecord;
import pro.deta.orion.command.audit.CommandAuditSink;
import pro.deta.orion.git.client.GitSshClientTransport.HostKeyRejectedException;
import pro.deta.orion.ssh.SshHostKeyDecision;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.BootstrapContext;
import pro.deta.orion.config.ConfigurationSecrets;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.keymaterial.ConfigurationCipherCapability;
import pro.deta.orion.git.nativestorage.NativeGitRepositoryProvider;
import pro.deta.orion.git.proxy.ProxyAwareNativeGitRepositoryProvider;
import pro.deta.orion.decision.ConnectionFailureHandler;
import java.util.function.BiFunction;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlStorage;
import pro.deta.orion.acl.storage.AccessControlStorageResolver;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.schema.config.ConfigurationProvider;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.util.ConfigurationContext;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Provides runtime services. Decision authorization is a temporary permissive stub;
 * task 02/01 must replace it with current hierarchical access checks.
 */
@Module
public class OrionRuntimeModule {
    private static final int MAX_PENDING_DECISIONS = 1024;

    @Provides
    @Singleton
    static DecisionRegistry decisionRegistry(OrionExecutor executor) {
        return new DecisionRegistry(MAX_PENDING_DECISIONS, executor, (actor, scope) -> true);
    }

    @Provides
    @Named("bootstrap-proxies")
    static Runnable bootstrapProxies(AccessControlStorage storage,
            ProxyAwareNativeGitRepositoryProvider provider, ConfigurationCipherCapability cipher,
            ConfigurationSecrets secrets, OrionDesiredState desiredState, OrionAccessControlServiceImpl acl,
            DecisionRegistry decisions,
            ConnectionFailureHandler connectionFailures,
            BiFunction<GitProxyBinding, HostKeyRejectedException, Decision> hostKeyDecisions) {
        return () -> {
            provider.connectionFailures(connectionFailures, hostKeyDecisions);
            BootstrapContext.adoptProxies(storage, provider, cipher);
            acl.reload("bootstrap proxy adoption");
            provider.activate(() -> desiredState.current().document(), secrets);
            OrionDesiredState.Snapshot snapshot = desiredState.current();
            for (Map.Entry<GitProxyBinding, GitProxyBinding> change
                    : provider.bootstrapChanges(snapshot.document()).entrySet()) {
                GitProxyBinding previous = change.getKey();
                GitProxyBinding replacement = change.getValue();
                Decision decision = bootstrapDecision(previous, replacement,
                        actor -> saveBootstrapConnection(acl, desiredState.current(), previous, replacement, actor));
                decisions.register(decision).valueOrFailure("Cannot register bootstrap connection decision");
            }
        };
    }

    private static Decision bootstrapDecision(GitProxyBinding previous, GitProxyBinding replacement,
            Function<PrincipalAddress, Result<Void>> save) {
        String description = "Bootstrap is running with the connection from orion.yml. "
                + "Update the XML connection to these parameters. Rejecting leaves the running connection unchanged."
                + "\nXML URL: " + previous.upstream() + "\nBootstrap URL: " + replacement.upstream()
                + "\nXML ref: " + previous.ref() + "\nBootstrap ref: " + replacement.ref()
                + "\nXML knownHosts:\n" + String.join("\n", new TreeSet<>(previous.knownHosts()))
                + "\nBootstrap knownHosts:\n" + String.join("\n", new TreeSet<>(replacement.knownHosts()));
        return new Decision(previous.alias(), Optional.empty(),
                "Update bootstrap connection " + previous.alias().value(), description,
                List.of(new DecisionAction("Update XML connection", true, save),
                        new DecisionAction("Reject", false, actor -> Result.of(null))));
    }

    private static Result<Void> saveBootstrapConnection(OrionAccessControlServiceImpl acl,
            OrionDesiredState.Snapshot snapshot, GitProxyBinding previous, GitProxyBinding replacement,
            PrincipalAddress actor) {
        try {
            acl.updatePrimaryConfiguration(snapshot.revision().orElseThrow(), document -> {
                if (!document.system().proxies().contains(previous)) {
                    throw new AccessControlConcurrentUpdateException("Bootstrap connection changed", null);
                }
                List<GitProxyBinding> bindings = new ArrayList<>(document.system().proxies());
                bindings.set(bindings.indexOf(previous), replacement);
                OrionDocument.SystemConfiguration system = document.system();
                return new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(),
                        system.https(), system.secrets(), bindings), document.organizations());
            }, new AccessControlSaveRequest("Reconcile bootstrap connection " + previous.alias().value()
                    + " approved by " + actor, UserEmail.EMPTY));
            return Result.of(null);
        } catch (RuntimeException failure) {
            return new Result.Failure<>(Result.FailureCode.GENERAL,
                    "Could not save bootstrap connection", failure);
        }
    }

    @Provides
    @Singleton
    public static ConnectionFailureHandler connectionFailures(DecisionRegistry decisions) {
        return new ConnectionFailureHandler(decisions);
    }

    @Provides
    @Singleton
    public static BiFunction<GitProxyBinding, HostKeyRejectedException, Decision> proxyHostKeyDecisions(
            OrionDesiredState desiredState, OrionAccessControlServiceImpl acl, CommandAuditSink audit) {
        return (connection, rejected) -> {
            int port = connection.upstream().getPort() < 0 ? 22 : connection.upstream().getPort();
            if (!connection.upstream().getHost().equalsIgnoreCase(rejected.host()) || port != rejected.port()) {
                throw new IllegalStateException("Rejected host key belongs to another server", rejected);
            }
            OrionDesiredState.Snapshot snapshot = desiredState.current();
            if (!snapshot.document().system().proxies().contains(connection)) {
                throw new AccessControlConcurrentUpdateException("Connection configuration changed", rejected);
            }
            String key = PublicKeyEntry.toString(rejected.serverKey());
            return SshHostKeyDecision.create(connection.alias(), Optional.empty(),
                    rejected.host(), rejected.port(), rejected.serverKey(),
                    new DecisionAction("Add and trust", true, actor -> trust(acl, audit, snapshot, connection, key, actor)))
                    .valueOrFailure("Could not prepare SSH host key decision");
        };
    }

    private static Result<Void> trust(OrionAccessControlServiceImpl acl, CommandAuditSink audit,
            OrionDesiredState.Snapshot snapshot,
            GitProxyBinding binding, String key, PrincipalAddress actor) {
        try {
            Set<String> keys = new TreeSet<>(binding.knownHosts());
            keys.add(key);
            GitProxyBinding replacement = new GitProxyBinding(binding.alias(), binding.upstream(), binding.ref(),
                    binding.credentialKind(), binding.secret(), binding.username(), keys);
            acl.updatePrimaryConfiguration(snapshot.revision().orElseThrow(), document -> {
                if (!document.system().proxies().contains(binding)) {
                    throw new AccessControlConcurrentUpdateException("Connection configuration changed", null);
                }
                List<GitProxyBinding> bindings = new ArrayList<>(document.system().proxies());
                bindings.set(bindings.indexOf(binding), replacement);
                OrionDocument.SystemConfiguration system = document.system();
                return new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(),
                        system.https(), system.secrets(), bindings), document.organizations());
            }, new AccessControlSaveRequest("Trust SSH host key for " + binding.alias().value()
                    + " approved by " + actor, UserEmail.EMPTY));
            recordTrustAudit(audit, binding, actor, "saved");
            return Result.of(null);
        } catch (AccessControlConcurrentUpdateException failure) {
            recordTrustAudit(audit, binding, actor, "configuration-conflict");
            return new Result.Failure<>(Result.FailureCode.GENERAL, "Connection configuration changed", failure);
        } catch (RuntimeException failure) {
            recordTrustAudit(audit, binding, actor, "operation-failed");
            return new Result.Failure<>(Result.FailureCode.GENERAL, "Could not save SSH host key", failure);
        }
    }

    private static void recordTrustAudit(CommandAuditSink audit, GitProxyBinding binding,
            PrincipalAddress actor, String result) {
        try {
            audit.record(new CommandAuditRecord(actor.toString(), UUID.randomUUID().toString(),
                    "", "", binding.publicRepositoryName(), "trust-host-key", Map.of("alias", binding.alias().value()),
                    result.equals("saved") ? "success" : "failed", result, 0, Map.of("scope", "system")));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(OrionRuntimeModule.class).warn("Could not record SSH trust operation audit");
        }
    }

    @Provides
    @Singleton
    static ConfigurationSecrets configurationSecrets(OrionDesiredState desiredState,
            ConfigurationCipherCapability cipher) {
        return new ConfigurationSecrets(() -> desiredState.current().document(), cipher);
    }

    @Provides
    static NativeGitRepositoryProvider nativeGitRepositoryProvider(ProxyAwareNativeGitRepositoryProvider provider) {
        return provider;
    }

    @Provides
    @Singleton
    static OrionConfiguration orionConfiguration(ConfigurationProvider configurationProvider) {
        return configurationProvider.readConfiguration();
    }

    @Provides
    @Singleton
    @Named("runtime")
    static AggregateStateMachine runtimeStateMachine(OrionRuntimeStateMachine stateMachine) {
        return stateMachine.aggregateStateMachine();
    }

    @Provides
    @Singleton
    static AgentSessionServer agentSessionServer(ConfigurationContext configuration) {
        return new AgentSessionServer(configuration.getBaseDir().resolve("agent-session-server"));
    }

    @Provides
    @Singleton
    static AgentControlHandler agentControlHandler(AgentSessionServer server) {
        return server;
    }

    @Provides
    OrionAccessControlService orionAccessControlService(
            OrionAccessControlServiceImpl orionAccessControlService) {
        return orionAccessControlService;
    }

    @Provides
    @Singleton
    static AccessControlStorage accessControlStorage(
            AccessControlStorageResolver accessControlStorageResolver) {
        return accessControlStorageResolver.resolve();
    }
}
