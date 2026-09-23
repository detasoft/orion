package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcProviderTest {
    @Test
    void keepsProviderSettingsAndSecretsWithinTheirOrganization() {
        OidcProvider google = new OidcProvider(
                "google", URI.create("https://accounts.google.com"), "google-client", "client-secret",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0);
        OidcProvider corporate = new OidcProvider(
                "google", URI.create("https://sso.example.test/realms/acme"), "corporate-client", "client-secret",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0);
        List<OidcProvider> providers = new ArrayList<>(List.of(google));
        OrionDocument.Organization defaults = organization("default", providers);
        OrionDocument.Organization acme = organization("acme", List.of(corporate));
        providers.clear();

        assertThat(defaults.oidcProviders()).containsExactly(google);
        assertThat(acme.oidcProviders()).containsExactly(corporate);
        assertThatThrownBy(() -> defaults.oidcProviders().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> organization("default", List.of(google, google)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate OIDC provider");
        assertThatThrownBy(() -> new OrionDocument.Organization(
                new OrganizationId("default"), null, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(google), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OIDC secret is unavailable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://sso.example.test", "https:/sso", "relative", "https://user:pass@sso.test",
            "https://sso.test?tenant=acme", "https://sso.test#tenant"})
    void rejectsInvalidIssuerAddresses(String issuer) {
        assertThatThrownBy(() -> new OidcProvider("sso", URI.create(issuer), "client", "secret",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OIDC issuer");
    }

    @Test
    void requiresProviderIdClientIdAndSecretReference() {
        URI issuer = URI.create("https://sso.example.test");
        assertThatThrownBy(() -> new OidcProvider("../sso", issuer, "client", "secret",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("provider id");
        assertThatThrownBy(() -> new OidcProvider("sso", issuer, " ", "secret",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client id");
        assertThatThrownBy(() -> new OidcProvider("sso", issuer, "client", "../other/secret",
                OidcProvider.DEFAULT_IDLE_TIMEOUT_SECONDS, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("secret reference");
    }

    @Test
    void validatesIndependentIdleAndReauthenticationTimeouts() {
        URI issuer = URI.create("https://sso.example.test");
        assertThat(new OidcProvider("sso", issuer, "client", "secret", 172800, 0)
                .reauthenticationTimeoutSeconds()).isZero();
        assertThatThrownBy(() -> new OidcProvider("sso", issuer, "client", "secret", 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OidcProvider("sso", issuer, "client", "secret", 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OidcProvider("sso", issuer, "client", "secret", Long.MAX_VALUE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OrionDocument.Organization organization(String id, List<OidcProvider> providers) {
        return new OrionDocument.Organization(new OrganizationId(id), null,
                List.of(), List.of(), List.of(), List.of(),
                List.of(new ConfigurationSecret("client-secret", "encrypted-value")), providers, List.of());
    }
}
