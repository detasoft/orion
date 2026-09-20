package pro.deta.orion.transport.git.auth;

import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootSshKeyEnrollmentSessionTest {
    @Test
    void completedEnrollmentKeepsTheConnectionRestricted() {
        SessionAttributes attributes = new SessionAttributes();
        ServerSession session = attributes.session;
        assertFalse(RootSshKeyEnrollmentSession.isRestricted(session));

        RootSshKeyEnrollmentSession.begin(session, "generation-1", List.of("ssh-rsa candidate"));
        assertTrue(RootSshKeyEnrollmentSession.isPending(session));
        assertTrue(RootSshKeyEnrollmentSession.isRestricted(session));
        assertEquals("generation-1", RootSshKeyEnrollmentSession.pending(session).expectedGeneration());

        RootSshKeyEnrollmentSession.complete(session);

        assertNull(RootSshKeyEnrollmentSession.pending(session));
        assertFalse(RootSshKeyEnrollmentSession.isPending(session));
        assertTrue(RootSshKeyEnrollmentSession.isRestricted(session));
    }

    @Test
    void restrictionRemainsVisibleBetweenCompletionAttributeUpdates() {
        SessionAttributes attributes = new SessionAttributes();
        RootSshKeyEnrollmentSession.begin(attributes.session, "generation-1", List.of("ssh-rsa candidate"));
        AtomicInteger observations = new AtomicInteger();
        attributes.afterMutation = () -> {
            observations.incrementAndGet();
            assertTrue(RootSshKeyEnrollmentSession.isRestricted(attributes.session),
                    "A channel checking access between attribute updates must remain restricted");
        };

        RootSshKeyEnrollmentSession.complete(attributes.session);

        assertTrue(observations.get() > 0);
        assertFalse(RootSshKeyEnrollmentSession.isPending(attributes.session));
        assertTrue(RootSshKeyEnrollmentSession.isRestricted(attributes.session));
    }

    private static final class SessionAttributes {
        private final Map<AttributeKey<?>, Object> values = new HashMap<>();
        private Runnable afterMutation = () -> {};
        private final ServerSession session = (ServerSession) Proxy.newProxyInstance(
                ServerSession.class.getClassLoader(), new Class<?>[]{ServerSession.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAttribute")) {
                        return values.get(args[0]);
                    }
                    Object previous;
                    if (method.getName().equals("setAttribute")) {
                        previous = values.put((AttributeKey<?>) args[0], args[1]);
                    } else if (method.getName().equals("removeAttribute")) {
                        previous = values.remove(args[0]);
                    } else {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    afterMutation.run();
                    return previous;
                });
    }
}
