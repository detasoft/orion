package pro.deta.orion.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAction;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.security.PublicKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Approval of one SSH server key with its trust operation bound before registration.
 * The trust action executes the captured operation, which owns saving this key;
 * the rejection action completes with a refusal. Completion reports the operation's outcome, not just the user's answer.
 * The supplied operation must be bound to the same server, key and configuration shown in the request.
 */
public final class SshHostKeyDecision extends Decision {
    private SshHostKeyDecision(Object resource, Optional<ConfigurationScope> scope,
            String title, String description, Function<PrincipalAddress, Result<Void>> trust) {
        super(resource, scope, title, description, List.of(new DecisionAction("Add and trust", trust),
                new DecisionAction("Reject", actor ->
                        new Result.Failure<>(Result.FailureCode.FALSE, "SSH host key was not trusted"))));
    }

    public static Result<SshHostKeyDecision> create(Object resource, Optional<ConfigurationScope> scope,
            String host, int port, PublicKey serverKey,
            Function<PrincipalAddress, Result<Void>> trust) {
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
