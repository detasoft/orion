package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrionXmlTest {
    private final OrionXmlSchema xmlSchema = new OrionXmlSchema();

    @Test
    void readsUnversionedAndExplicitLegacyAclDocuments() throws Exception {
        String unversioned = testResource("pro/deta/orion/schema/acl/legacy-orion.xml");
        String explicit = unversioned.replace("<AccessControl>", "<AccessControl schemaVersion=\"1\">");

        OrionDocument fromUnversioned = read(unversioned);
        OrionDocument fromExplicit = read(explicit);

        assertThat(fromUnversioned).isEqualTo(fromExplicit);
        assertThat(fromUnversioned.organizations()).isEmpty();
        assertThat(fromUnversioned.system().accessControl().getUsers().getFirst().getId()).isEqualTo("root");
    }

    @Test
    void roundTripsAVersionTwoDocument() throws Exception {
        String xml = testResource("pro/deta/orion/schema/orion/orion-v2.xml");

        OrionDocument document = read(xml);
        String serialized = write(document);
        OrionDocument roundTripped = read(serialized);

        assertThat(roundTripped).isEqualTo(document);
        assertThat(serialized).contains("<orion schemaVersion=\"2\">");
        assertThat(serialized).contains("<organization id=\"acme\">");
        assertThat(serialized).contains("<team id=\"platform\">");
        assertThat(serialized).contains("<repository id=\"api\">");
        assertThat(OrionXml.currentSchemaVersion()).isEqualTo(OrionXmlSchemaVersion.V2);
    }

    @Test
    void writesEquivalentDocumentsDeterministically() throws Exception {
        AccessControl.User alpha = user("alpha");
        AccessControl.User zulu = user("zulu");
        OrionDocument.Organization alphaOrganization = organization("alpha");
        OrionDocument.Organization zuluOrganization = organization("zulu");
        OrionDocument first = document(
                new AccessControl(List.of(zulu, alpha), List.of(), List.of()),
                List.of(zuluOrganization, alphaOrganization));
        OrionDocument second = document(
                new AccessControl(List.of(alpha, zulu), List.of(), List.of()),
                List.of(alphaOrganization, zuluOrganization));

        assertThat(write(first)).isEqualTo(write(second));
    }

    @Test
    void canonicalizesUnorderedWireCollectionsOnRead() throws Exception {
        String xml = """
                <orion schemaVersion="2">
                  <system>
                    <accessControl>
                      <users>
                        <user id="z-user"><credentials/><roles/><grants/></user>
                        <user id="a-user"><credentials/><roles/><grants/></user>
                      </users>
                      <roles/>
                      <grants>
                        <grant id="values">
                          <info>
                            <expression><key>WRITE</key><value>z</value></expression>
                            <expression><key>READ</key><value>a</value></expression>
                          </info>
                        </grant>
                      </grants>
                    </accessControl>
                  </system>
                  <organizations>
                    <organization id="z-org"><teams/></organization>
                    <organization id="a-org">
                      <teams>
                        <team id="z-team"><repositories/></team>
                        <team id="a-team">
                          <repositories>
                            <repository id="z-repo"/>
                            <repository id="a-repo"/>
                          </repositories>
                        </team>
                      </teams>
                    </organization>
                  </organizations>
                </orion>
                """;

        OrionDocument document = read(xml);

        assertThat(document.organizations()).extracting(organization -> organization.id().value())
                .containsExactly("a-org", "z-org");
        assertThat(document.organizations().getFirst().teams()).extracting(team -> team.id().value())
                .containsExactly("a-team", "z-team");
        assertThat(document.organizations().getFirst().teams().getFirst().repositories())
                .extracting(repository -> repository.id().value())
                .containsExactly("a-repo", "z-repo");
        assertThat(document.system().accessControl().getUsers()).extracting(AccessControl.User::getId)
                .containsExactly("a-user", "z-user");
        assertThat(document.system().accessControl().getGrants().getFirst().getInfo())
                .extracting(expression -> expression.getKey().name())
                .containsExactly("READ", "WRITE");
    }

    @Test
    void generatedSchemaDescribesAndValidatesVersionTwo() throws Exception {
        String schema = xmlSchema.document();
        String xml = testResource("pro/deta/orion/schema/orion/orion-v2.xml");

        OrionXmlSchema.ValidationResult result = validate(xml);

        assertThat(result.valid()).isTrue();
        assertThat(schema).contains("name=\"orion\"");
        assertThat(schema).contains("name=\"schemaVersion\"");
        assertThat(schema).contains("<xs:enumeration value=\"2\"/>");
        assertThat(schema).contains("name=\"system\"");
        assertThat(schema).contains("name=\"organization\"");
        assertThat(schema).contains("name=\"team\"");
        assertThat(schema).contains("name=\"repository\"");
    }

    @Test
    void rejectsUnknownVersionTwoElementsAndAttributes() throws Exception {
        String xml = testResource("pro/deta/orion/schema/orion/orion-v2.xml");
        String unknownElement = xml.replace("</system>", "<unknown/></system>");
        String unknownAttribute = xml.replace(
                "<orion schemaVersion=\"2\">",
                "<orion schemaVersion=\"2\" extra=\"x\">");

        assertInvalidAgainstSchemaAndReader(unknownElement);
        assertInvalidAgainstSchemaAndReader(unknownAttribute);
    }

    @Test
    void rejectsUnknownRootsAndMissingOrUnsupportedVersions() {
        assertThatThrownBy(() -> read("<Orion schemaVersion=\"2\"/>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported Orion XML root: Orion");
        assertThatThrownBy(() -> read("<orion/>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("requires schemaVersion");
        assertThatThrownBy(() -> read("<orion schemaVersion=\"3\"/>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported Orion XML schema version: 3");
        assertThatThrownBy(() -> read("<AccessControl schemaVersion=\"2\"/>"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported AccessControl XML schema version: 2");
    }

    @Test
    void rejectsDuplicateHierarchyAndAclIds() {
        String duplicateOrganizations = minimalV2(
                """
                <organization id="acme"><teams/></organization>
                <organization id="acme"><teams/></organization>
                """,
                "");
        String duplicateUsers = minimalV2(
                "",
                """
                <user id="root"><credentials/><roles/><grants/></user>
                <user id="ROOT"><credentials/><roles/><grants/></user>
                """);

        assertThatThrownBy(() -> read(duplicateOrganizations))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate organization id: acme");
        assertThatThrownBy(() -> read(duplicateUsers))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate ACL user id: ROOT");
    }

    @Test
    void rejectsDocumentTypeDeclarations() {
        String xml = """
                <!DOCTYPE orion [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <orion schemaVersion="2">
                  <system><accessControl><users/><roles/><grants/></accessControl></system>
                  <organizations/>
                </orion>
                """;

        assertThatThrownBy(() -> read(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Cannot detect Orion XML schema version");
    }

    private void assertInvalidAgainstSchemaAndReader(String xml) throws Exception {
        OrionXmlSchema.ValidationResult result = validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
        assertThatThrownBy(() -> read(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not conform to Orion XML v2 schema");
    }

    private OrionXmlSchema.ValidationResult validate(String xml) throws Exception {
        return xmlSchema.validate(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static OrionDocument read(String xml) throws IOException {
        return OrionXml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String write(OrionDocument document) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(document, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static OrionDocument document(
            AccessControl accessControl,
            List<OrionDocument.Organization> organizations) {
        return new OrionDocument(new OrionDocument.SystemConfiguration(accessControl), organizations);
    }

    private static AccessControl.User user(String id) {
        return new AccessControl.User(id, null, null, null, List.of(), List.of(), List.of());
    }

    private static OrionDocument.Organization organization(String id) {
        OrionDocument.Repository repository =
                new OrionDocument.Repository(new RepositoryId("repository"), null);
        OrionDocument.Team team =
                new OrionDocument.Team(new TeamId("team"), null, List.of(repository));
        return new OrionDocument.Organization(new OrganizationId(id), null, List.of(team));
    }

    private static String minimalV2(String organizations, String users) {
        return """
                <orion schemaVersion="2">
                  <system>
                    <accessControl>
                      <users>%s</users>
                      <roles/>
                      <grants/>
                    </accessControl>
                  </system>
                  <organizations>%s</organizations>
                </orion>
                """.formatted(users, organizations);
    }

    private static String testResource(String resourceName) throws Exception {
        try (InputStream input = OrionXmlTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(input).as("test resource %s", resourceName).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
