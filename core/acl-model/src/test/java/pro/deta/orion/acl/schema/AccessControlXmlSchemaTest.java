package pro.deta.orion.acl.schema;

import jakarta.xml.bind.annotation.XmlRootElement;
import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.v1.AccessControlV1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessControlXmlSchemaTest {
    private final AccessControlXmlSchema xmlSchema = new AccessControlXmlSchema();

    @Test
    void schemaValidatesRuntimeXmlSerialization() throws Exception {
        String schema = xmlSchema.document();
        String xml = serialize(ACLUtil.generateDefaultAccessControl("root-password-hash"));

        AccessControlXmlSchema.ValidationResult result = validate(xml);

        assertThat(result.valid()).isTrue();
        assertThat(xml).contains("schemaVersion=\"1\"");

        assertThat(schema).contains("name=\"AccessControl\"");
        assertThat(schema).contains("name=\"schemaVersion\"");
        assertThat(schema).contains("<xs:enumeration value=\"1\"/>");
        assertThat(schema).contains("name=\"user\"");
        assertThat(schema).contains("name=\"role\"");
        assertThat(schema).contains("name=\"grant\"");
        assertThat(schema).contains("<xs:enumeration value=\"ARGON2\"/>");
        assertThat(schema).contains("<xs:enumeration value=\"JWT_SIGNING_PUBLIC_KEY\"/>");
        assertThat(schema).contains("<xs:enumeration value=\"ADMIN\"/>");
        assertThat(schema).contains("<xs:enumeration value=\"NETWORK_PORT\"/>");
    }

    @Test
    void schemaContractLivesInVersionedDtoPackage() {
        assertThat(AccessControl.class.getAnnotation(XmlRootElement.class)).isNull();
        assertThat(AccessControlV1.class.getAnnotation(XmlRootElement.class)).isNotNull();
        assertThat(AccessControlV1.class.getPackageName()).endsWith(".schema.v1");
        assertThat(AccessControlXml.currentSchemaVersion()).isEqualTo(AccessControlXmlSchemaVersion.V1);
    }

    @Test
    void schemaRejectsLegacyPluralCollectionItemNames() throws Exception {
        String legacyXml = """
                <AccessControl>
                  <users>
                    <users>
                      <id>root</id>
                    </users>
                  </users>
                </AccessControl>
                """;

        AccessControlXmlSchema.ValidationResult result = validate(legacyXml);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        String xml = """
                <AccessControl schemaVersion="999">
                </AccessControl>
                """;

        assertThatThrownBy(() -> AccessControlXml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Unsupported ACL XML schema version: 999");
    }

    @Test
    void schemaRejectsUnsupportedSchemaVersion() throws Exception {
        String xml = """
                <AccessControl schemaVersion="999">
                </AccessControl>
                """;

        AccessControlXmlSchema.ValidationResult result = validate(xml);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("999");
    }

    @Test
    void readsLegacyPluralCollectionItemNames() throws Exception {
        String legacyXml = """
                <AccessControl>
                  <users>
                    <users>
                      <id>root</id>
                      <email>root@orion.pro</email>
                      <credentials>
                        <credentials>
                          <type>SHA1</type>
                          <value>root-password-hash</value>
                        </credentials>
                      </credentials>
                      <roles>
                        <roles>ROOT</roles>
                      </roles>
                      <grants/>
                    </users>
                  </users>
                  <roles>
                    <roles>
                      <id>ROOT</id>
                      <grantReferences>
                        <grantReferences>CONNECT</grantReferences>
                      </grantReferences>
                      <grants/>
                    </roles>
                  </roles>
                  <grants>
                    <grants>
                      <id>CONNECT</id>
                      <info>
                        <info>
                          <key>NETWORK_SOURCE</key>
                          <value>127.0.0.1</value>
                        </info>
                      </info>
                    </grants>
                  </grants>
                </AccessControl>
                """;

        AccessControl acl = AccessControlXml.read(new ByteArrayInputStream(legacyXml.getBytes(StandardCharsets.UTF_8)));

        assertThat(acl.getUsers()).hasSize(1);
        assertThat(acl.getUsers().getFirst().getId()).isEqualTo("root");
        assertThat(acl.getUsers().getFirst().getCredentials()).hasSize(1);
        assertThat(acl.getUsers().getFirst().getRoles()).containsExactly("ROOT");
        assertThat(acl.getRoles().getFirst().getGrantReferences()).containsExactly("CONNECT");
        assertThat(acl.getGrants().getFirst().getInfo().getFirst().getKey())
                .isEqualTo(AccessControl.GrantKey.NETWORK_SOURCE);
    }

    @Test
    void readsMissingCollectionsAsEmptyLists() throws Exception {
        String xml = """
                <AccessControl>
                  <users>
                    <user>
                      <id>root</id>
                    </user>
                  </users>
                </AccessControl>
                """;

        AccessControl acl = AccessControlXml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(acl.getRoles()).isEmpty();
        assertThat(acl.getGrants()).isEmpty();
        assertThat(acl.getUsers().getFirst().getCredentials()).isEmpty();
        assertThat(acl.getUsers().getFirst().getRoles()).isEmpty();
        assertThat(acl.getUsers().getFirst().getGrants()).isEmpty();
    }

    @Test
    void mapsCurrentModelThroughVersionedDto() throws Exception {
        AccessControlDraft draft = new AccessControlDraft();
        draft.getUsers().add(ACLUtil.createUser("server", "server@orion.pro")
                .addCredential(AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY, "server-key", "public-key"));
        draft.getGrants().add(ACLUtil.createGrant("PORT_22")
                .addKey(AccessControl.GrantKey.NETWORK_PORT, "22"));
        AccessControl accessControl = draft.toAccessControl();

        AccessControl read = AccessControlXml.read(new ByteArrayInputStream(serialize(accessControl)
                .getBytes(StandardCharsets.UTF_8)));

        assertThat(read.getUsers().getFirst().getCredentials().getFirst().getType())
                .isEqualTo(AccessControl.CredentialType.JWT_SIGNING_PUBLIC_KEY);
        assertThat(read.getUsers().getFirst().getCredentials().getFirst().getKeyId()).isEqualTo("server-key");
        assertThat(read.getGrants().getFirst().getInfo().getFirst().getKey())
                .isEqualTo(AccessControl.GrantKey.NETWORK_PORT);
    }

    @Test
    void readsLegacyFixtureWithoutSchemaVersionAndWritesLatestVersion() throws Exception {
        String legacyXml = testResource("pro/deta/orion/acl/schema/legacy-orion.xml");

        AccessControl accessControl = AccessControlXml.read(new ByteArrayInputStream(legacyXml.getBytes(StandardCharsets.UTF_8)));
        String latestXml = serialize(accessControl);

        assertThat(accessControl.getUsers()).hasSize(1);
        assertThat(accessControl.getUsers().getFirst().getId()).isEqualTo("root");
        assertThat(accessControl.getRoles().getFirst().getId()).isEqualTo("ROOT");
        assertThat(accessControl.getGrants().getFirst().getId()).isEqualTo("ALL_REPOSITORY");
        assertThat(legacyXml).doesNotContain("schemaVersion=");
        assertThat(latestXml).contains("schemaVersion=\"1\"");
        assertThat(validate(latestXml).valid()).isTrue();
    }

    private String serialize(AccessControl accessControl) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AccessControlXml.write(accessControl, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private AccessControlXmlSchema.ValidationResult validate(String xml) throws Exception {
        return xmlSchema.validate(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String testResource(String resourceName) throws Exception {
        try (InputStream input = AccessControlXmlSchemaTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(input).as("test resource %s", resourceName).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
