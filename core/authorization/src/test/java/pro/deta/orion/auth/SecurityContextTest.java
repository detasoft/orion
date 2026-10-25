package pro.deta.orion.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityContextTest {
    @Test
    void operationSnapshotResolvesOnceAndRetainsRequestMetadata() {
        UserIdentity allowed = new InternalUserImpl("operator", List.of());
        AtomicReference<UserIdentity> current = new AtomicReference<>(allowed);
        AtomicInteger resolutions = new AtomicInteger();
        UserIdentity sessionUser = new InternalUserImpl("operator", () -> {
            resolutions.incrementAndGet();
            return current.get();
        });
        SshConnectionCredentials credentials = new SshConnectionCredentials("SHA256:key", List.of());
        SecurityContext session = SecurityContext.createContext().withUserIdentity(sessionUser)
                .withRequestId("request-42").withSshConnectionCredentials(credentials);

        SecurityContext running = session.snapshot();
        current.set(SecurityContext.ANONYMOUS);

        assertThat(running.getUserIdentity()).isSameAs(allowed);
        assertThat(running.getUserIdentity().getGrants()).isEmpty();
        assertThat(running.getRequestId()).isEqualTo("request-42");
        assertThat(running.getSshConnectionCredentials()).isSameAs(credentials);
        assertThat(resolutions).hasValue(1);
        assertThat(session.snapshot().getUserIdentity().isAnonymous()).isTrue();
        assertThat(resolutions).hasValue(2);
        assertThat(session.getUserIdentity()).isSameAs(sessionUser);
    }

    @Test
    void newContextStartsAsAnonymousWithoutRequestId() {
        SecurityContext context = SecurityContext.createContext();

        assertThat(context.getUserIdentity()).isSameAs(SecurityContext.ANONYMOUS);
        assertThat(context.getUserIdentity().isAnonymous()).isTrue();
        assertThat(context.getUserIdentity().getUserId()).isEmpty();
        assertThat(context.getUserIdentity().getGrants()).isEmpty();
        assertThat(context.getRequestId()).isNull();
        assertThat(context.getSshConnectionCredentials()).isEqualTo(SshConnectionCredentials.empty());
        assertThat(context.formatShort()).isEqualTo("UserIdentity.ANONYMOUS");
    }

    @Test
    void sshConnectionCredentialsAreCopiedAndRedactedFromStringForms() {
        String key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestSecret operator";
        List<String> candidates = new java.util.ArrayList<>(List.of(key));
        SshConnectionCredentials credentials = new SshConnectionCredentials("SHA256:current", candidates);
        SecurityContext context = SecurityContext.createContext().withSshConnectionCredentials(credentials);

        candidates.clear();

        assertThat(credentials.candidatePublicKeys()).containsExactly(key);
        assertThat(context.getSshConnectionCredentials()).isSameAs(credentials);
        assertThat(credentials.toString()).contains("SHA256:current", "candidateCount=1").doesNotContain(key);
        assertThat(context.toString()).doesNotContain(key);
    }

    @Test
    void userIdentityAndRequestIdCanBeSetFluently() {
        InternalUserImpl user = new InternalUserImpl("writer", List.of());
        SecurityContext context = SecurityContext.createContext();

        SecurityContext returned = context
                .withUserIdentity(user)
                .withRequestId("request-42");

        assertThat(returned).isSameAs(context);
        assertThat(context.getUserIdentity()).isSameAs(user);
        assertThat(context.getRequestId()).isEqualTo("request-42");
        assertThat(context.formatShort()).isEqualTo(user.toString());
    }

    @Test
    void nullUserIdentityResetsContextToAnonymous() {
        SecurityContext context = SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("writer", List.of()));

        context.withUserIdentity(null);

        assertThat(context.getUserIdentity()).isSameAs(SecurityContext.ANONYMOUS);
        assertThat(context.formatShort()).isEqualTo("UserIdentity.ANONYMOUS");
    }

    @Test
    void requestIdCanBeCleared() {
        SecurityContext context = SecurityContext.createContext()
                .withRequestId("request-42");

        context.withRequestId(null);

        assertThat(context.getRequestId()).isNull();
    }
}
