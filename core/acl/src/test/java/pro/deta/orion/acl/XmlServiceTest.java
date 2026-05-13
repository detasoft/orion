package pro.deta.orion.acl;

import org.junit.jupiter.api.Test;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.ACLUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class XmlServiceTest {
    private final XmlService xmlService = new XmlService();

    @Test
    void serializesCollectionItemsWithSingularElementNames() throws Exception {
        String xml = serialize(ACLUtil.generateDefaultAccessControl("root-password-hash"));

        assertThat(xml).contains("<users>");
        assertThat(xml).contains("<user>");
        assertThat(xml).doesNotContain("<users>\n    <users>");

        assertThat(xml).contains("<roles>");
        assertThat(xml).contains("<role>ROOT</role>");
        assertThat(xml).contains("<grantReferences>");
        assertThat(xml).contains("<grantReference>CONNECT</grantReference>");
        assertThat(xml).doesNotContain("<grantReferences>\n        <grantReferences>");

        assertThat(xml).contains("<grants>");
        assertThat(xml).contains("<grant>");
        assertThat(xml).doesNotContain("<grants>\n    <grants>");

        assertThat(xml).contains("<credentials>");
        assertThat(xml).contains("<credential>");
        assertThat(xml).doesNotContain("<credentials>\n        <credentials>");

        assertThat(xml).contains("<info>");
        assertThat(xml).contains("<expression>");
        assertThat(xml).doesNotContain("<info>\n        <info>");
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

        AccessControl acl = xmlService.deserialize(new ByteArrayInputStream(legacyXml.getBytes(StandardCharsets.UTF_8)));

        assertThat(acl.getUsers()).hasSize(1);
        assertThat(acl.getUsers().getFirst().getId()).isEqualTo("root");
        assertThat(acl.getUsers().getFirst().getCredentials()).hasSize(1);
        assertThat(acl.getUsers().getFirst().getRoles()).containsExactly("ROOT");

        assertThat(acl.getRoles()).hasSize(1);
        assertThat(acl.getRoles().getFirst().getGrantReferences()).containsExactly("CONNECT");

        assertThat(acl.getGrants()).hasSize(1);
        assertThat(acl.getGrants().getFirst().getInfo()).hasSize(1);
        assertThat(acl.getGrants().getFirst().getInfo().getFirst().getKey())
                .isEqualTo(AccessControl.GrantKey.NETWORK_SOURCE);
    }

    private String serialize(AccessControl accessControl) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xmlService.serialize(accessControl, output);
        return output.toString(StandardCharsets.UTF_8);
    }
}
