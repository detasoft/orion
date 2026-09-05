package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        assertThat(fromUnversioned.system().https()).isEmpty();
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
        OrionDocument.Repository repository = document.organizations().getFirst()
                .teams().getFirst().repositories().getFirst();
        assertThat(repository.defaultBranch()).isEqualTo("refs/heads/main");
        assertThat(repository.remotes()).extracting(remote -> remote.alias().value())
                .containsExactly("upstream");
        assertThat(serialized).contains("<reference>github-token</reference>");
        assertThat(serialized).doesNotContain("github-token@");
        assertThat(OrionXml.currentSchemaVersion()).isEqualTo(OrionXmlSchemaVersion.V2);
    }

    @Test
    void roundTripsHttpsAndAcmeMaterialReferencesInTheSystemConfiguration() throws Exception {
        OrionMaterialReference identity = new OrionMaterialReference("https-identity", 3);
        OrionMaterialReference issuer = new OrionMaterialReference("server-root", 1);
        OrionMaterialReference clientRoot = new OrionMaterialReference("client-root", 2);
        OrionAcmeConfiguration acme = new OrionAcmeConfiguration(
                true,
                URI.create("acme://letsencrypt.org/staging"),
                "admin@example.test",
                List.of("example.test", "www.example.test"),
                "ORION",
                Optional.of(new OrionMaterialReference("acme-account", 4)),
                30,
                40,
                true,
                false);
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                true,
                "127.0.0.1",
                8443,
                URI.create("https://example.test"),
                Optional.of(identity),
                Optional.of(issuer),
                OrionHttpsConfiguration.ClientAuthentication.REQUIRED,
                List.of(clientRoot),
                Optional.of(acme));
        OrionDocument document = new OrionDocument(
                new OrionDocument.SystemConfiguration(
                        new AccessControl(),
                        Optional.of(https)),
                List.of());

        String serialized = write(document);
        OrionDocument restored = read(serialized);

        assertThat(restored).isEqualTo(document);
        assertThat(serialized).contains("<https>");
        assertThat(serialized).contains("<identity alias=\"https-identity\" version=\"3\"");
        assertThat(serialized).contains("<accountMaterial alias=\"acme-account\" version=\"4\"");
        assertThat(serialized).doesNotContain("PRIVATE KEY");
    }

    @Test
    void rejectsInvalidHttpsAndAcmeDesiredState() {
        OrionMaterialReference identity = new OrionMaterialReference("identity", 1);

        assertThatThrownBy(() -> new OrionMaterialReference(" ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrionMaterialReference("identity", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrionHttpsConfiguration(
                true,
                "localhost",
                8443,
                URI.create("https://localhost"),
                Optional.of(identity),
                Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.REQUIRED,
                List.of(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust anchors");
        assertThatThrownBy(() -> new OrionAcmeConfiguration(
                true,
                URI.create("acme://letsencrypt.org/staging"),
                "admin@example.test",
                List.of("example.test", "example.test"),
                null,
                Optional.of(new OrionMaterialReference("account", 1)),
                30,
                30,
                false,
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate ACME domain");
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
    void writesEquivalentRemoteConfigurationDeterministically() throws Exception {
        OrionDocument first = documentWithRemotes(List.of(
                outboundRemote("zulu", orderedTriggers(false), orderedMappings(false)),
                outboundRemote("alpha", orderedTriggers(true), orderedMappings(true))));
        OrionDocument second = documentWithRemotes(List.of(
                outboundRemote("alpha", orderedTriggers(false), orderedMappings(false)),
                outboundRemote("zulu", orderedTriggers(true), orderedMappings(true))));

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
        assertThat(schema).contains("name=\"defaultBranch\"");
        assertThat(schema).contains("name=\"policy\"");
        assertThat(schema).contains("name=\"remotes\"");
        assertThat(schema).contains("name=\"remote\"");
        assertThat(schema).contains("name=\"refMappings\"");
    }

    @Test
    void suppliesSafeDefaultsWhenRepositoryConfigurationIsOmitted() throws Exception {
        String xml = minimalV2(
                """
                <organization id="acme">
                  <teams>
                    <team id="platform">
                      <repositories><repository id="api"/></repositories>
                    </team>
                  </teams>
                </organization>
                """,
                "");

        OrionDocument.Repository repository = read(xml).organizations().getFirst()
                .teams().getFirst().repositories().getFirst();

        assertThat(repository.defaultBranch()).isEqualTo(OrionDocument.Repository.DEFAULT_BRANCH);
        assertThat(repository.policy()).isEqualTo(RepositoryPolicy.safeDefaults());
        assertThat(repository.remotes()).isEmpty();
    }

    @Test
    void rejectsInvalidRemoteTopologyOnRead() {
        String remote = wireRemote("upstream", "PRIMARY", "https://github.com/acme/project.git");
        String duplicateAliases = documentWithWireRemotes(remote + remote);
        String nonPrimaryUpstream = documentWithWireRemotes(
                wireRemote("upstream", "OUTBOUND_ONLY", "https://github.com/acme/project.git"));

        assertThatThrownBy(() -> read(duplicateAliases))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("duplicate remote alias: upstream");
        assertThatThrownBy(() -> read(nonPrimaryUpstream))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("PRIMARY remote");
    }

    @Test
    void rejectsSecretBearingRemoteUrisOnRead() {
        String xml = documentWithWireRemotes(
                wireRemote("upstream", "PRIMARY", "https://github-token@github.com/acme/project.git"));

        assertThatThrownBy(() -> read(xml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must not contain credentials");
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
                new OrionDocument.Repository(
                        new RepositoryId("repository"),
                        null,
                        "refs/heads/main",
                        RepositoryPolicy.safeDefaults(),
                        List.of());
        OrionDocument.Team team =
                new OrionDocument.Team(new TeamId("team"), null, List.of(repository));
        return new OrionDocument.Organization(new OrganizationId(id), null, List.of(team));
    }

    private static OrionDocument documentWithRemotes(List<RepositoryRemote> remotes) {
        OrionDocument.Repository repository = new OrionDocument.Repository(
                new RepositoryId("repository"),
                null,
                OrionDocument.Repository.DEFAULT_BRANCH,
                RepositoryPolicy.safeDefaults(),
                remotes);
        OrionDocument.Team team = new OrionDocument.Team(
                new TeamId("team"),
                null,
                List.of(repository));
        OrionDocument.Organization organization = new OrionDocument.Organization(
                new OrganizationId("organization"),
                null,
                List.of(team));
        return document(new AccessControl(), List.of(organization));
    }

    private static RepositoryRemote outboundRemote(
            String alias,
            Set<RemoteTrigger> triggers,
            List<RemoteRefMapping> mappings) {
        return new RepositoryRemote(
                new RemoteAlias(alias),
                RemoteRole.OUTBOUND_ONLY,
                RemoteProvider.GENERIC,
                URI.create("https://git.example.test/acme/project.git"),
                new ConfigurationSecretReference(
                        ConfigurationSecretReference.Scope.REPOSITORY,
                        "external-token"),
                triggers,
                mappings,
                RemoteUpdatePolicy.fastForwardOnly());
    }

    private static Set<RemoteTrigger> orderedTriggers(boolean reversed) {
        if (reversed) {
            return new LinkedHashSet<>(List.of(
                    RemoteTrigger.PERIODIC_AUDIT,
                    RemoteTrigger.LOCAL_REF_UPDATE));
        }
        return new LinkedHashSet<>(List.of(
                RemoteTrigger.LOCAL_REF_UPDATE,
                RemoteTrigger.PERIODIC_AUDIT));
    }

    private static List<RemoteRefMapping> orderedMappings(boolean reversed) {
        RemoteRefMapping alpha = new RemoteRefMapping("refs/heads/a", "refs/heads/a");
        RemoteRefMapping zulu = new RemoteRefMapping("refs/heads/z", "refs/heads/z");
        return reversed ? List.of(zulu, alpha) : List.of(alpha, zulu);
    }

    private static String documentWithWireRemotes(String remotes) {
        return minimalV2(
                """
                <organization id="acme">
                  <teams>
                    <team id="platform">
                      <repositories>
                        <repository id="api"><remotes>%s</remotes></repository>
                      </repositories>
                    </team>
                  </teams>
                </organization>
                """.formatted(remotes),
                "");
    }

    private static String wireRemote(String alias, String role, String uri) {
        return """
                <remote alias="%s">
                  <role>%s</role>
                  <provider>GITHUB</provider>
                  <uri>%s</uri>
                  <credential><scope>REPOSITORY</scope><reference>github-token</reference></credential>
                  <triggers><trigger>LOCAL_REF_UPDATE</trigger></triggers>
                  <refMappings>
                    <refMapping>
                      <source>refs/heads/*</source>
                      <destination>refs/heads/*</destination>
                    </refMapping>
                  </refMappings>
                  <updatePolicy>
                    <allowForceUpdates>false</allowForceUpdates>
                    <allowDeletes>false</allowDeletes>
                    <allowTagRewrites>false</allowTagRewrites>
                  </updatePolicy>
                </remote>
                """.formatted(alias, role, uri);
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
