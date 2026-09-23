package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.schema.acl.AccessControl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitProxyXmlTest {
    private static final String FIRST_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB";
    private static final String SECOND_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIC";

    @Test
    void roundTripsCanonicalSystemBindingAndPreservesItDuringAclUpdates() throws Exception {
        OrionDocument document = read(document(proxy("configuration", "HTTPS://GIT.EXAMPLE:443/a/../repo",
                "main", "TOKEN", "<secret>bootstrap-token</secret>")));
        String serialized = write(document);

        assertThat(serialized).contains("https://git.example/repo", "refs/heads/main", "bootstrap-token");
        assertThat(read(serialized)).isEqualTo(document);
        assertThat(write(document.replaceAccessControl(new AccessControl())))
                .contains("<proxies>", "alias=\"configuration\"");
    }

    @Test
    void readsOldConfigurationWithoutAddingProxies() throws Exception {
        assertThat(write(read(document("")))).doesNotContain("<proxies");
    }

    @Test
    void acceptsDifferentRefsAndOwnsTheProxyCollection() throws Exception {
        String main = proxy("configuration", "https://git.example/repo", "main",
                "PASSWORD", "<secret>bootstrap-token</secret><username>operator</username>");
        String material = proxy("material", "https://git.example/repo", "material",
                "TOKEN", "<secret>bootstrap-token</secret>");
        OrionDocument first = read(document(material + main));
        assertThat(write(first)).isEqualTo(write(read(document(main + material))));
        assertThat(read(write(first))).isEqualTo(first);
        var system = first.system();
        List<GitProxyBinding> bindings = new ArrayList<>(system.proxies());
        var copied = new OrionDocument.SystemConfiguration(system.accessControl(), system.https(),
                system.secrets(), bindings);
        bindings.clear();
        assertThat(copied.proxies()).hasSize(2);
        assertThatThrownBy(copied.proxies()::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesFileTransportWithoutASecret() throws Exception {
        OrionDocument document = read(document(proxy("configuration", "file:///srv/git/a/../repo.git",
                "main", "NONE", "")));
        assertThat(document.system().proxies().getFirst().upstream().toASCIIString())
                .isEqualTo("file:///srv/git/repo.git");
        assertThat(document.system().proxies().getFirst().secret()).isEmpty();
        assertThat(read(write(document))).isEqualTo(document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTPS://git.example:443/repo", "https://git.example/repo"})
    void keepsEncodedPathsAndNonDefaultPortsDistinct(String upstream) throws Exception {
        String first = proxy("configuration", upstream, "main", "TOKEN",
                "<secret>bootstrap-token</secret>");
        String port = proxy("port", "https://git.example:8443/repo", "main", "TOKEN",
                "<secret>bootstrap-token</secret>");
        String encoded = proxy("encoded", "https://git.example/a%2Fb", "main", "TOKEN",
                "<secret>bootstrap-token</secret>");
        String path = proxy("path", "https://git.example/a/b", "main", "TOKEN",
                "<secret>bootstrap-token</secret>");
        assertThat(read(document(first + port + encoded + path)).system().proxies()).hasSize(4);
    }

    @Test
    void rejectsDifferentAliasesForTheSameCanonicalUpstreamAndRef() {
        String first = proxy("configuration", "https://git.example:443/repo", "main",
                "TOKEN", "<secret>bootstrap-token</secret>");
        String second = proxy("material", "HTTPS://GIT.EXAMPLE/repo", "refs/heads/main",
                "TOKEN", "<secret>bootstrap-token</secret>");
        assertThatThrownBy(() -> read(document(first + second)))
                .isInstanceOf(IOException.class).hasMessageContaining("duplicate proxy upstream/ref");
    }

    @Test
    void rejectsAnAliasCollisionAcrossDifferentUpstreams() {
        String first = proxy("configuration", "https://git.example/repo", "main",
                "TOKEN", "<secret>bootstrap-token</secret>");
        String second = proxy("configuration", "https://git.example/other", "main",
                "TOKEN", "<secret>bootstrap-token</secret>");
        assertThatThrownBy(() -> read(document(first + second)))
                .isInstanceOf(IOException.class).hasMessageContaining("duplicate proxy id");
    }

    @Test
    void requiresTheReferencedSecretInTheSystemOwner() {
        String binding = proxy("configuration", "https://git.example/repo", "main",
                "TOKEN", "<secret>missing</secret>");
        assertThatThrownBy(() -> read(document(binding)))
                .isInstanceOf(IOException.class).hasMessageContaining("proxy secret is unavailable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://user:private-token@git.example/repo",
            "https://git.example/repo?token=private-token", "https://git.example/repo#private-token",
            "ftp://git.example/repo", "https:/repo", "https://git.example/private-token invalid"})
    void rejectsUnsafeUpstreamWithoutPrintingIt(String uri) {
        assertThatThrownBy(() -> read(document(proxy("configuration", uri, "main",
                "TOKEN", "<secret>bootstrap-token</secret>"))))
                .isInstanceOf(IOException.class).hasMessageNotContaining("private-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "refs/heads/../main", "refs/heads/.hidden", "refs/heads/main.lock"})
    void rejectsInvalidRefs(String ref) {
        assertThatThrownBy(() -> read(document(proxy("configuration", "https://git.example/repo", ref,
                "TOKEN", "<secret>bootstrap-token</secret>"))))
                .isInstanceOf(IOException.class).hasMessageContaining("proxy ref");
    }

    @Test
    void preservesMultipleSshKeysAndSystemSecretReference() throws Exception {
        String binding = proxy("material", "ssh://git@git.example:22/repo", "main", "PRIVATE_KEY",
                "<secret>bootstrap-token</secret><knownHosts><key>" + FIRST_KEY + "</key><key>"
                        + SECOND_KEY + "</key><key>" + FIRST_KEY + "</key></knownHosts>");
        OrionDocument parsed = read(document(binding));
        assertThat(parsed.system().proxies().getFirst().knownHosts())
                .containsExactlyInAnyOrder(FIRST_KEY, SECOND_KEY);
        assertThatThrownBy(() -> parsed.system().proxies().getFirst().knownHosts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        String serialized = write(parsed);
        assertThat(serialized).contains("ssh://git@git.example/repo", "PRIVATE_KEY", FIRST_KEY, SECOND_KEY);
        assertThat(read(serialized)).isEqualTo(parsed);
    }

    @Test
    void sharesPasswordKindBetweenHttpAndSshWhileKeepingTheirUsernamesSeparate() throws Exception {
        String http = proxy("configuration", "https://git.example/repo", "main", "PASSWORD",
                "<secret>bootstrap-token</secret><username>operator</username>");
        String ssh = proxy("material", "ssh://git@git.example/repo", "main", "PASSWORD",
                "<secret>bootstrap-token</secret>");
        OrionDocument document = read(document(http + ssh));
        assertThat(document.system().proxies()).extracting(GitProxyBinding::credentialKind)
                .containsExactly(GitCredentialKind.PASSWORD, GitCredentialKind.PASSWORD);
        assertThat(read(write(document))).isEqualTo(document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "ssh-ed25519 !not-base64", "host ssh-ed25519 AAAA", "ssh-ed25519"})
    void rejectsMalformedSshTrust(String knownHosts) {
        String binding = proxy("material", "ssh://git@git.example/repo", "main", "PRIVATE_KEY",
                "<secret>bootstrap-token</secret><knownHosts><key>" + knownHosts + "</key></knownHosts>");
        assertThatThrownBy(() -> read(document(binding)))
                .isInstanceOf(IOException.class).hasMessageContaining("host key");
    }

    @Test
    void rejectsAuthThatDoesNotMatchTheTransport() {
        assertThatThrownBy(() -> read(document(proxy("configuration", "https://git.example/repo", "main",
                "PRIVATE_KEY", "<secret>bootstrap-token</secret>"))))
                .isInstanceOf(IOException.class).hasMessageContaining("credential kind");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "<secret>bootstrap-token</secret>",
            "<secret>bootstrap-token</secret><username>bad:user</username>",
            "<secret>bootstrap-token</secret><username>operator</username>"
                    + "<knownHosts><key>" + FIRST_KEY + "</key></knownHosts>"})
    void rejectsIncompleteOrInconsistentBasicAuth(String auth) {
        assertThatThrownBy(() -> read(document(proxy("configuration", "https://git.example/repo", "main",
                "PASSWORD", auth)))).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UpperCase", "bootstrap/proxy-internal", "../configuration"})
    void requiresACanonicalPublicAlias(String alias) {
        assertThatThrownBy(() -> read(document(proxy(alias, "https://git.example/repo", "main",
                "TOKEN", "<secret>bootstrap-token</secret>"))))
                .isInstanceOf(IOException.class).hasMessageContaining("alias");
    }

    private static String document(String proxies) {
        return "<orion schemaVersion=\"2\"><system><accessControl><users/><roles/><grants/></accessControl>"
                + "<secrets><secret id=\"bootstrap-token\"><envelope>opaque-envelope</envelope></secret></secrets>"
                + (proxies.isEmpty() ? "" : "<proxies>" + proxies + "</proxies>")
                + "</system><organizations/></orion>";
    }

    private static String proxy(String alias, String upstream, String ref, String kind, String auth) {
        return "<proxy alias=\"" + alias + "\"><upstream>" + upstream + "</upstream><ref>" + ref
                + "</ref><credentialKind>" + kind + "</credentialKind>" + auth + "</proxy>";
    }

    private static OrionDocument read(String xml) throws IOException {
        return OrionXml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String write(OrionDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(document, output);
        return output.toString(StandardCharsets.UTF_8);
    }
}
