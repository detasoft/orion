package pro.deta.orion.git.proxy;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.config.BootstrapSourceConfig;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapGitLocationTest {
    @Test
    void classifiesSupportedRemoteSchemes() {
        assertThat(BootstrapGitLocation.isRemote("git+ssh://git@example.test/repo.git"))
                .isTrue();
        assertThat(BootstrapGitLocation.isRemote("git+http://example.test/repo.git"))
                .isTrue();
        assertThat(BootstrapGitLocation.isRemote("git+https://example.test/repo.git"))
                .isTrue();
        assertThat(BootstrapGitLocation.isRemote("git+file:///srv/git/repo.git"))
                .isTrue();
    }

    @Test
    void leavesLocalLocationDirect() {
        assertThat(BootstrapGitLocation.isRemote("local:orion")).isFalse();
    }

    @Test
    void recognizesMalformedSupportedSchemeForSafeValidation() {
        assertThat(BootstrapGitLocation.isRemote(
                "git+https://example.test/repo.git?credential=plain secret"))
                .isTrue();
    }

    @Test
    void parsesRemoteWithoutPuttingSecretsInTheUri() {
        BootstrapSourceConfig config = config(
                "git+https://example.test/team/orion.git?ref=configuration",
                Map.of(
                        "credentialKind", "http-bearer",
                        "credential", "env:ORION_GIT_CREDENTIAL"));

        BootstrapGitLocation location = BootstrapGitLocation.parse(config);

        assertThat(location.remoteUri()).isEqualTo(
                URI.create("https://example.test/team/orion.git"));
        assertThat(location.refName()).isEqualTo("refs/heads/configuration");
        assertThat(location.credentialReference()).isEqualTo("env:ORION_GIT_CREDENTIAL");
        assertThat(location.credentialKind()).isEqualTo(BootstrapGitCredentialKind.HTTP_BEARER);
        assertThat(location.safeDescription()).isEqualTo(
                "git+https://example.test/team/orion.git");
    }

    @Test
    void preservesEncodedRepositoryPathWhenBuildingTransportUri() {
        BootstrapGitLocation location = BootstrapGitLocation.parse(config(
                "git+file:///srv/git/team%20config.git?ref=main",
                fileAuth()));

        assertThat(location.remoteUri()).isEqualTo(
                URI.create("file:///srv/git/team%20config.git"));
    }

    @Test
    void acceptsFileTransportWithoutCredential() {
        BootstrapGitLocation location = BootstrapGitLocation.parse(config(
                "git+file:///srv/git/config.git",
                fileAuth()));

        assertThat(location.credentialKind()).isEqualTo(BootstrapGitCredentialKind.NONE);
        assertThat(location.credentialReference()).isNull();
    }

    @Test
    void usesTheSameProxyIdentityForDifferentPathsOnOneUpstreamRef() {
        BootstrapSourceConfig configuration = config(
                "git+file:///srv/git/config.git",
                fileAuth());
        BootstrapSourceConfig material = config(
                "git+file:///srv/git/config.git",
                fileAuth());
        configuration.setPath("orion.xml");
        material.setPath("security/material.p12");

        assertThat(BootstrapGitLocation.parse(configuration).proxyName())
                .isEqualTo(BootstrapGitLocation.parse(material).proxyName());
    }

    @Test
    void canonicalizesEquivalentNetworkAndFileUpstreams() {
        BootstrapGitLocation networkAlias = BootstrapGitLocation.parse(config(
                "git+HTTPS://Example.TEST:443/team/./orion.git",
                validAuth()));
        BootstrapGitLocation equivalentNetworkAlias = BootstrapGitLocation.parse(config(
                "git+https://example.test/team/orion.git",
                validAuth()));
        BootstrapGitLocation fileAlias = BootstrapGitLocation.parse(config(
                "git+file:///srv/git/team/../orion.git",
                fileAuth()));
        BootstrapGitLocation equivalentFileAlias = BootstrapGitLocation.parse(config(
                "git+file:///srv/git/orion.git",
                fileAuth()));

        assertThat(networkAlias.proxyName()).isEqualTo(equivalentNetworkAlias.proxyName());
        assertThat(fileAlias.proxyName()).isEqualTo(equivalentFileAlias.proxyName());
    }

    @Test
    void canonicalIdentityPreservesEncodedPathUsernameAndNonDefaultPort() {
        BootstrapGitLocation encoded = BootstrapGitLocation.parse(config(
                "git+https://example.test/team%2Frepo.git",
                validAuth()));
        BootstrapGitLocation slash = BootstrapGitLocation.parse(config(
                "git+https://example.test/team/repo.git",
                validAuth()));
        BootstrapGitLocation sshUser = BootstrapGitLocation.parse(config(
                "git+ssh://git@example.test/repo.git",
                auth("ssh-password", "env:SSH_PASSWORD")));
        BootstrapGitLocation anotherSshUser = BootstrapGitLocation.parse(config(
                "git+ssh://deploy@example.test/repo.git",
                auth("ssh-password", "env:SSH_PASSWORD")));
        BootstrapGitLocation nonDefaultPort = BootstrapGitLocation.parse(config(
                "git+https://example.test:8443/repo.git",
                validAuth()));
        BootstrapGitLocation defaultPort = BootstrapGitLocation.parse(config(
                "git+https://example.test/repo.git",
                validAuth()));

        assertThat(encoded.proxyName()).isNotEqualTo(slash.proxyName());
        assertThat(sshUser.proxyName()).isNotEqualTo(anotherSshUser.proxyName());
        assertThat(nonDefaultPort.proxyName()).isNotEqualTo(defaultPort.proxyName());
    }

    @Test
    void differentUpstreamsOrRefsUseDifferentProxyIdentities() {
        BootstrapSourceConfig main = config(
                "git+https://example.test/orion.git",
                validAuth());
        BootstrapSourceConfig anotherRef = config(
                "git+https://example.test/orion.git",
                validAuth());
        anotherRef.setRef("refs/heads/configuration");
        BootstrapSourceConfig anotherUpstream = config(
                "git+https://mirror.example.test/orion.git",
                validAuth());

        assertThat(BootstrapGitLocation.parse(main).proxyName())
                .isNotEqualTo(BootstrapGitLocation.parse(anotherRef).proxyName());
        assertThat(BootstrapGitLocation.parse(main).proxyName())
                .isNotEqualTo(BootstrapGitLocation.parse(anotherUpstream).proxyName());
    }

    @Test
    void parsesSupportedNetworkCredentialKinds() {
        assertThat(BootstrapGitLocation.parse(config(
                "git+http://example.test/repo.git",
                auth("http-basic", "env:HTTP_PASSWORD", "credentialUsername", "orion")))
                .credentialUsername()).isEqualTo("orion");
        assertThat(BootstrapGitLocation.parse(config(
                "git+ssh://git@example.test/repo.git",
                auth("ssh-password", "env:SSH_PASSWORD"))).credentialKind())
                .isEqualTo(BootstrapGitCredentialKind.SSH_PASSWORD);
        assertThat(BootstrapGitLocation.parse(config(
                "git+ssh://git@example.test/repo.git",
                auth("ssh-private-key", "file:/run/secrets/git-key"))).credentialKind())
                .isEqualTo(BootstrapGitCredentialKind.SSH_PRIVATE_KEY);
    }

    @Test
    void rejectsCredentialKindsThatDoNotMatchTransport() {
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+https://example.test/repo.git",
                auth("ssh-password", "env:SSH_PASSWORD"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git credential kind does not match transport");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+ssh://git@example.test/repo.git",
                auth("http-bearer", "env:HTTP_TOKEN"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git credential kind does not match transport");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+file:///srv/git/config.git",
                auth("http-bearer", "env:HTTP_TOKEN"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git credential kind does not match transport");
    }

    @Test
    void rejectsSymbolicOrUnsafeSelectedRefs() {
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+file:///srv/git/config.git?ref=HEAD",
                fileAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git bootstrap ref must be a full Git ref name");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+file:///srv/git/config.git?ref=refs/heads/../secret",
                fileAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git bootstrap ref must be a full Git ref name");
    }

    @Test
    void rejectsSecretBearingOrUnsafeLocations() {
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+https://user:plain@example.test/repo.git",
                validAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git bootstrap URI must not contain credentials");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+https://plain-token@example.test/repo.git",
                validAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git bootstrap URI must not contain credentials");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+ssh://user:encoded%3Asecret@example.test/repo.git",
                validAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git bootstrap URI must not contain credentials")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+https://example.test/repo.git?credential=plain",
                validAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git bootstrap URI contains unsupported parameters");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+https://example.test/repo.git?credential=plain secret",
                validAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid remote Git bootstrap URI")
                .hasMessageNotContaining("plain secret");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+https://example.test/repo.git#credential",
                validAuth())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git bootstrap URI must not contain a fragment");
        assertThatThrownBy(() -> BootstrapGitLocation.parse(config(
                "git+https://example.test/repo.git",
                Map.of(
                        "credentialKind", "http-bearer",
                        "credential", "plain-secret"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote Git credential must use env: or file:");
    }

    private static BootstrapSourceConfig config(
            String location,
            Map<String, String> auth) {
        BootstrapSourceConfig config = new BootstrapSourceConfig();
        config.setLocation(location);
        config.setRef("refs/heads/main");
        config.setPath("orion.xml");
        config.setAuth(auth);
        return config;
    }

    private static Map<String, String> validAuth() {
        return auth("http-bearer", "env:GIT_TOKEN");
    }

    private static Map<String, String> fileAuth() {
        return Map.of();
    }

    private static Map<String, String> auth(String kind, String credential, String... extra) {
        java.util.LinkedHashMap<String, String> auth = new java.util.LinkedHashMap<>();
        auth.put("credentialKind", kind);
        auth.put("credential", credential);
        for (int index = 0; index < extra.length; index += 2) {
            auth.put(extra[index], extra[index + 1]);
        }
        return Map.copyOf(auth);
    }
}
