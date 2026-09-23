package pro.deta.orion.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.PendingDecision;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.util.Result;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Requests approval of an SSH server key within the connection owner's management scope.
 * The pending decision completes with action "add" or "reject" and the answering principal;
 * cancellation follows PendingDecision's contract. Approval alone does not change trusted keys.
 * The caller retains the connection and key, owns saving trust and retrying, and dispatches that
 * continuation to its executor. Host and port identify the server without exposing credentials.
 */
public final class SshHostKeyDecision {
    private SshHostKeyDecision() {
    }

    public static Result<PendingDecision> request(DecisionRegistry registry,
            Optional<ConfigurationScope> scope, String host, int port, PublicKey serverKey) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(serverKey, "serverKey");
        if (host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("SSH server requires a host and a port between 1 and 65535");
        }
        String key;
        String fingerprint;
        try {
            key = PublicKeyEntry.toString(serverKey);
            fingerprint = KeyUtils.getFingerPrint(serverKey);
        } catch (IllegalArgumentException unsupported) {
            return new Result.Failure<>(Result.FailureCode.NOT_SUPPORTED, "Unsupported SSH host key", unsupported);
        }
        String address = "[" + host + "]:" + port;
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put("add", "Add and trust");
        actions.put("reject", "Reject");
        return registry.register(scope, "Trust SSH host key for " + address,
                "Server: " + address + "\nKey: " + key + "\nFingerprint: " + fingerprint, actions);
    }
}
