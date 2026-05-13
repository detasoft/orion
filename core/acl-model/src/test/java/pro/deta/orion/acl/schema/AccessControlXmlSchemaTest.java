package pro.deta.orion.acl.schema;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AccessControlXmlSchemaTest {
    private final AccessControlXmlSchema xmlSchema = new AccessControlXmlSchema();

    @Test
    void schemaValidatesRuntimeXmlSerialization() throws Exception {
        String schema = xmlSchema.document();
        String xml = serialize(ACLUtil.generateDefaultAccessControl("root-password-hash"));

        AccessControlXmlSchema.ValidationResult result = validate(xml);

        assertThat(result.valid()).isTrue();

        assertThat(schema).contains("name=\"AccessControl\"");
        assertThat(schema).contains("name=\"user\"");
        assertThat(schema).contains("name=\"role\"");
        assertThat(schema).contains("name=\"grant\"");
        assertThat(schema).contains("<xs:enumeration value=\"ARGON2\"/>");
        assertThat(schema).contains("<xs:enumeration value=\"ADMIN\"/>");
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

    private String serialize(AccessControl accessControl) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AccessControlXml.write(accessControl, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private AccessControlXmlSchema.ValidationResult validate(String xml) throws Exception {
        return xmlSchema.validate(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
