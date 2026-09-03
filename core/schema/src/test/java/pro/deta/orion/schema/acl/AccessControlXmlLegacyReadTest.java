package pro.deta.orion.schema.acl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessControlXmlLegacyReadTest {
    @Test
    void rejectsUnsupportedLegacySchemaVersion() {
        String xml = """
                <AccessControl schemaVersion="999">
                </AccessControl>
                """;

        assertThatThrownBy(() -> AccessControlXml.read(bytes(xml)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Unsupported ACL XML schema version: 999");
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

        AccessControl acl = AccessControlXml.read(bytes(legacyXml));

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
                <AccessControl schemaVersion="1">
                  <users>
                    <user>
                      <id>root</id>
                    </user>
                  </users>
                </AccessControl>
                """;

        AccessControl acl = AccessControlXml.read(bytes(xml));

        assertThat(acl.getRoles()).isEmpty();
        assertThat(acl.getGrants()).isEmpty();
        assertThat(acl.getUsers().getFirst().getCredentials()).isEmpty();
        assertThat(acl.getUsers().getFirst().getRoles()).isEmpty();
        assertThat(acl.getUsers().getFirst().getGrants()).isEmpty();
    }

    @Test
    void readsLegacyFixtureWithoutSchemaVersion() throws Exception {
        String legacyXml = testResource("pro/deta/orion/schema/acl/legacy-orion.xml");

        AccessControl accessControl = AccessControlXml.read(bytes(legacyXml));

        assertThat(accessControl.getUsers().getFirst().getId()).isEqualTo("root");
        assertThat(accessControl.getRoles().getFirst().getId()).isEqualTo("ROOT");
        assertThat(accessControl.getGrants().getFirst().getId()).isEqualTo("ALL_REPOSITORY");
    }

    private static ByteArrayInputStream bytes(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String testResource(String resourceName) throws Exception {
        try (InputStream input = AccessControlXmlLegacyReadTest.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertThat(input).as("test resource %s", resourceName).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
