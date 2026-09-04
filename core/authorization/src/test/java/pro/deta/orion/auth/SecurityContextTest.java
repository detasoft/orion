package pro.deta.orion.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityContextTest {
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
