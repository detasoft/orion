package pro.deta.orion;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.acl.storage.AccessControlSaveRequest;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.decision.PendingDecision;
import pro.deta.orion.internal.UserEmail;
import pro.deta.orion.schema.orion.GitProxyBinding;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.util.Result;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * One system proxy's host-key approval, bound to its observed configuration revision.
 * The caller supplies the binding used by the failed connection and owns the executor and retry.
 * Approval schedules a configuration update; success is reported only after saving and reloading it.
 * Cancellation wins only while the decision is pending; an accepted answer owns the subsequent save.
 */
public final class GitHostKeyDecision {
    private final PendingDecision pending;
    private final CompletableFuture<Result<GitProxyBinding>> result = new CompletableFuture<>();

    private GitHostKeyDecision(PendingDecision pending, OrionAccessControlServiceImpl configuration,
            Executor executor, String revision, GitProxyBinding binding, String key) {
        this.pending = pending;
        pending.result().whenComplete((decision, failure) -> {
            if (failure != null || !decision.action().equals("add")) {
                result.complete(new Result.Failure<>(Result.FailureCode.FALSE, "Host key was not trusted"));
                return;
            }
            try {
                executor.execute(() -> result.complete(save(configuration, revision, binding, key, decision)));
            } catch (RuntimeException rejected) {
                result.complete(new Result.Failure<>(Result.FailureCode.GENERAL,
                        "Could not schedule host key configuration update", rejected));
            }
        });
    }

    public static Result<GitHostKeyDecision> request(DecisionRegistry registry,
            OrionAccessControlServiceImpl configuration, Executor executor, OrionDesiredState.Snapshot snapshot,
            GitProxyBinding binding, PublicKey serverKey) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(serverKey, "serverKey");
        if (!"ssh".equals(binding.upstream().getScheme())) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Host key approval requires SSH");
        }
        if (snapshot.revision().isEmpty() || snapshot.revision().orElseThrow().isBlank()
                || !snapshot.document().system().proxies().contains(binding)) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND, "Connection configuration is unavailable");
        }
        String key;
        String fingerprint;
        try {
            key = PublicKeyEntry.toString(serverKey);
            fingerprint = KeyUtils.getFingerPrint(serverKey);
        } catch (IllegalArgumentException unsupported) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Unsupported SSH host key", unsupported);
        }
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put("add", "Add and trust");
        actions.put("reject", "Reject");
        Result<PendingDecision> registered = registry.register(Optional.empty(),
                "Trust SSH host key for " + binding.alias().value(),
                "Connection: " + binding.upstream().toASCIIString() + "\nKey: " + key
                        + "\nFingerprint: " + fingerprint, actions);
        return switch (registered) {
            case Result.Failure<PendingDecision> failure -> new Result.Failure<>(failure);
            case Result.Success<PendingDecision> success -> Result.of(new GitHostKeyDecision(success.value(),
                    configuration, executor, snapshot.revision().orElseThrow(), binding, key));
        };
    }

    public DecisionRequest request() {
        return pending.request();
    }

    public CompletionStage<Result<GitProxyBinding>> result() {
        return result.minimalCompletionStage();
    }

    public boolean cancel() {
        return pending.cancel();
    }

    private static Result<GitProxyBinding> save(OrionAccessControlServiceImpl configuration, String revision,
            GitProxyBinding binding, String key, Decision decision) {
        try {
            Set<String> keys = new TreeSet<>(binding.knownHosts());
            keys.add(key);
            GitProxyBinding replacement = new GitProxyBinding(binding.alias(), binding.upstream(), binding.ref(),
                    binding.credentialKind(), binding.secret(), binding.username(), keys);
            configuration.updatePrimaryConfiguration(revision, document -> {
                if (!document.system().proxies().contains(binding)) {
                    throw new AccessControlConcurrentUpdateException("Connection configuration changed", null);
                }
                List<GitProxyBinding> bindings = new ArrayList<>(document.system().proxies());
                bindings.set(bindings.indexOf(binding), replacement);
                OrionDocument.SystemConfiguration system = document.system();
                return new OrionDocument(new OrionDocument.SystemConfiguration(system.accessControl(),
                        system.https(), system.secrets(), bindings), document.organizations());
            }, new AccessControlSaveRequest("Trust SSH host key for " + binding.alias().value()
                    + " approved by " + decision.actor(), UserEmail.EMPTY));
            return Result.of(replacement);
        } catch (AccessControlConcurrentUpdateException conflict) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, "Connection configuration changed", conflict);
        } catch (RuntimeException failure) {
            return new Result.Failure<>(Result.FailureCode.GENERAL, "Could not save trusted host key", failure);
        }
    }
}
