package pro.deta.orion.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionAnswer;
import pro.deta.orion.decision.DecisionRequest;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;
import pro.deta.orion.util.Result;

import java.security.PublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Approval of one SSH server key with its trust operation bound before registration.
 * Choosing "add" executes the captured operation, which owns saving this key and retrying the connection;
 * "reject" completes with a refusal. Completion reports the operation's outcome, not just the user's answer.
 * The supplied operation must be bound to the same server, key and configuration shown in the request.
 */
public final class SshHostKeyDecision extends Decision {
    private final Function<PrincipalAddress, Result<Void>> trust;

    private SshHostKeyDecision(DecisionRequest request,
            Function<PrincipalAddress, Result<Void>> trust) {
        super(request);
        this.trust = trust;
    }

    public static Result<SshHostKeyDecision> create(Optional<ConfigurationScope> scope,
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
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put("add", "Add and trust");
        actions.put("reject", "Reject");
        DecisionRequest request = new DecisionRequest(UUID.randomUUID(), Instant.now(), scope,
                "Trust SSH host key for " + address,
                "Server: " + address + "\nKey: " + key + "\nFingerprint: " + fingerprint, actions);
        return Result.of(new SshHostKeyDecision(request, trust));
    }

    @Override
    protected Result<Void> execute(DecisionAnswer answer) {
        return answer.action().equals("add") ? trust.apply(answer.actor())
                : new Result.Failure<>(Result.FailureCode.FALSE, "SSH host key was not trusted");
    }
}
