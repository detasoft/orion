package pro.deta.orion.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.util.Result;

import java.security.PublicKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Approval of one SSH server key with its trust operation bound before registration.
 * The supplied trust action defines whether approval persists the key or permits only the current attempt;
 * the rejection action completes with a refusal. Completion reports the operation's outcome, not just the user's answer.
 * The supplied operation must be bound to the same server, key and configuration shown in the request.
 */
public final class SshHostKeyDecision extends Decision {
    private SshHostKeyDecision(Object resource, Optional<ConfigurationScope> scope,
            String title, String description, DecisionAction trust) {
        super(resource, scope, title, description, List.of(trust,
                new DecisionAction("Reject", false, actor -> Result.of(null))));
    }

    public static Result<SshHostKeyDecision> create(Object resource, Optional<ConfigurationScope> scope,
            String host, int port, PublicKey serverKey,
            DecisionAction trust) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(serverKey, "serverKey");
        Objects.requireNonNull(trust, "trust");
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
        return Result.of(new SshHostKeyDecision(resource, scope, "Trust SSH host key for " + address,
                "Server: " + address + "\nKey: " + key + "\nFingerprint: " + fingerprint, trust));
    }
}
