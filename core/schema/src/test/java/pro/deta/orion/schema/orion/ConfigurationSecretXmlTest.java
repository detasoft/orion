package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.OrionV2Mapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationSecretXmlTest {
    private static final String ENVELOPE = "orion-secret.version=1.alias=Y29uZmlndXJhdGlvbi12MQ"
            + ".key-version=1.wrap=QUVTV3JhcA.cipher=QUVTL0dDTS9Ob1BhZGRpbmc.encoding=YmFzZTY0dXJs"
            + ".wrapped-key=AQID.nonce=AAAAAAAAAAAAAAAA.ciphertext=BAUG";

    @Test
    void preservesDistinctSecretsWithTheSameIdInDifferentScopes() throws Exception {
        String xml = fixture();
        for (String scope : new String[]{"system", "organization", "repository"}) {
            xml = addSecrets(xml, scope, secret("github-token", ENVELOPE + "-" + scope));
        }

        OrionDocument document = read(xml);
        String serialized = write(document);

        assertThat(read(serialized)).isEqualTo(document);
        for (String scope : new String[]{"system", "organization", "repository"}) {
            assertThat(serialized).contains(ENVELOPE + "-" + scope);
        }
        assertThat(document.toString()).doesNotContain(ENVELOPE);
        assertThat(OrionV2Mapper.fromCurrent(document).toString()).doesNotContain(ENVELOPE);
    }

    @Test
    void readsExistingXmlWithoutIntroducingSecretEntries() throws Exception {
        OrionDocument document = read(fixture());

        assertThat(document.system().secrets()).isEmpty();
        assertThat(document.organizations().getFirst().secrets()).isEmpty();
        assertThat(document.organizations().getFirst().teams().getFirst()
                .repositories().getFirst().secrets()).isEmpty();
        assertThat(write(document)).doesNotContain("<secrets");
    }

    @Test
    void ownsSecretCollectionsAtEachScope() {
        ConfigurationSecret secret = new ConfigurationSecret("github-token", ENVELOPE);
        List<ConfigurationSecret> source = new ArrayList<>(List.of(secret));
        OrionDocument.SystemConfiguration system = new OrionDocument.SystemConfiguration(
                new AccessControl(), Optional.empty(), source, List.of());
        OrionDocument.Organization organization = new OrionDocument.Organization(
                new OrganizationId("acme"), null, List.of(), List.of(), List.of(), List.of(), source);
        OrionDocument.Repository repository = new OrionDocument.Repository(
                new RepositoryId("api"), null, "refs/heads/main", RepositoryPolicy.safeDefaults(),
                List.of(), List.of(), List.of(), source);

        source.clear();

        for (List<ConfigurationSecret> secrets : List.of(system.secrets(), organization.secrets(),
                repository.secrets())) {
            assertThat(secrets).containsExactly(secret);
            assertThatThrownBy(secrets::clear).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsEmptyEnvelopes(String envelope) throws Exception {
        String xml = addSecrets(fixture(), "system", secret("github-token", envelope));

        assertThatThrownBy(() -> read(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("secret envelope must not be empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"system", "organization", "repository"})
    void rejectsDuplicateIdsWithinOneScope(String scope) throws Exception {
        String xml = addSecrets(fixture(), scope,
                secret("github-token", ENVELOPE) + secret("github-token", ENVELOPE + "-other"));

        assertThatThrownBy(() -> read(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate secret id")
                .satisfies(ConfigurationSecretXmlTest::assertSafeDiagnostic);
    }

    @Test
    void preservesSecretsWhenReplacingAccessControl() throws Exception {
        String xml = fixture();
        for (String scope : new String[]{"system", "organization", "repository"}) {
            xml = addSecrets(xml, scope, secret("github-token", ENVELOPE));
        }
        OrionDocument document = read(xml);

        OrionDocument replaced = read(write(document.replaceAccessControl(new AccessControl())));

        assertThat(replaced.system().accessControl()).isEqualTo(new AccessControl());
        assertThat(replaced.organizations()).isEqualTo(document.organizations());
        assertThat(write(replaced)).contains(ENVELOPE);
        assertThat(read(write(replaced)).replaceAccessControl(document.system().accessControl()))
                .isEqualTo(document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"system", "organization", "repository"})
    void serializesSecretIdsInStableOrder(String scope) throws Exception {
        String first = addSecrets(fixture(), scope,
                secret("zulu", ENVELOPE) + secret("alpha", ENVELOPE));
        String second = addSecrets(fixture(), scope,
                secret("alpha", ENVELOPE) + secret("zulu", ENVELOPE));

        assertThat(write(read(first))).isEqualTo(write(read(second)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Not-Canonical", "../token", "other/token"})
    void rejectsInvalidSecretIdsWithoutPrintingTheirValues(String id) throws Exception {
        String xml = addSecrets(fixture(), "system", secret(id, ENVELOPE));

        assertThatThrownBy(() -> read(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("secret id")
                .satisfies(ConfigurationSecretXmlTest::assertSafeDiagnostic);
    }

    private static void assertSafeDiagnostic(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        assertThat(output.toString()).doesNotContain(ENVELOPE);
    }

    private static String fixture() throws IOException {
        try (var input = ConfigurationSecretXmlTest.class.getResourceAsStream("orion-v2.xml")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String addSecrets(String xml, String scope, String secrets) {
        int closingTag = xml.lastIndexOf("</" + scope + ">");
        return xml.substring(0, closingTag) + "<secrets>" + secrets + "</secrets>" + xml.substring(closingTag);
    }

    private static String secret(String id, String envelope) {
        return "<secret id=\"" + id + "\"><envelope>" + envelope + "</envelope></secret>";
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
