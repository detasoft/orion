package pro.deta.orion.transport.git.auth;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.server.session.ServerSession;

import java.util.List;

/**
 * Connection-local root recovery state retained between SSH authentication and the dedicated enrollment command.
 */
public final class RootSshKeyEnrollmentSession {
    private static final AttributeRepository.AttributeKey<PendingEnrollment> PENDING =
            new AttributeRepository.AttributeKey<>();
    private static final AttributeRepository.AttributeKey<Boolean> COMPLETED =
            new AttributeRepository.AttributeKey<>();

    private RootSshKeyEnrollmentSession() {
    }

    public static void begin(ServerSession session, String expectedGeneration, List<String> publicKeys) {
        session.setAttribute(PENDING, new PendingEnrollment(expectedGeneration, publicKeys));
    }

    public static PendingEnrollment pending(ServerSession session) {
        return session.getAttribute(PENDING);
    }

    public static boolean isPending(ServerSession session) {
        return pending(session) != null;
    }

    public static boolean isRestricted(ServerSession session) {
        return isPending(session) || Boolean.TRUE.equals(session.getAttribute(COMPLETED));
    }

    public static void complete(ServerSession session) {
        session.removeAttribute(PENDING);
        session.setAttribute(COMPLETED, true);
    }

    public record PendingEnrollment(String expectedGeneration, List<String> publicKeys) {
        public PendingEnrollment {
            if (expectedGeneration == null || expectedGeneration.isBlank()) {
                throw new IllegalArgumentException("Expected root generation is required");
            }
            publicKeys = List.copyOf(publicKeys);
            if (publicKeys.isEmpty()) {
                throw new IllegalArgumentException("At least one SSH public key is required");
            }
        }
    }
}
