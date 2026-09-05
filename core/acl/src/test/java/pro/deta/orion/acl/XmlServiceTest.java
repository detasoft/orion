package pro.deta.orion.acl;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionHttpsConfiguration;
import pro.deta.orion.schema.orion.OrionMaterialReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class XmlServiceTest {
    private final XmlService xmlService = new XmlService();

    @Test
    void serializesVersionTwoWithSingularCollectionItemNames() throws Exception {
        String xml = serialize(ACLUtil.generateDefaultAccessControl("root-password-hash"));

        assertThat(xml).contains("<orion schemaVersion=\"2\">");
        assertThat(xml).contains("<system>");
        assertThat(xml).contains("<accessControl>");
        assertThat(xml).contains("<organizations/>");
        assertThat(xml).doesNotContain("<AccessControl");

        assertThat(xml).contains("<users>");
        assertThat(xml).contains("<user id=\"root\">");
        assertThat(xml).doesNotContain("<users>\n    <users>");

        assertThat(xml).contains("<roles>");
        assertThat(xml).contains("<role>ROOT</role>");
        assertThat(xml).contains("<grantReferences>");
        assertThat(xml).contains("<grantReference>CONNECT</grantReference>");
        assertThat(xml).doesNotContain("<grantReferences>\n        <grantReferences>");

        assertThat(xml).contains("<grants>");
        assertThat(xml).contains("<grant id=\"ALL_REPOSITORY\">");
        assertThat(xml).doesNotContain("<grants>\n    <grants>");

        assertThat(xml).contains("<credentials>");
        assertThat(xml).contains("<credential>");
        assertThat(xml).doesNotContain("<credentials>\n        <credentials>");

        assertThat(xml).contains("<info>");
        assertThat(xml).contains("<expression>");
        assertThat(xml).doesNotContain("<info>\n        <info>");
    }

    @Test
    void readsVersionTwoAndProjectsTheSystemAccessControl() throws Exception {
        String xml = """
                <orion schemaVersion="2">
                  <system>
                    <accessControl>
                      <users>
                        <user id="root">
                          <credentials/>
                          <roles/>
                          <grants/>
                        </user>
                      </users>
                      <roles/>
                      <grants/>
                    </accessControl>
                  </system>
                  <organizations>
                    <organization id="acme"><teams/></organization>
                  </organizations>
                </orion>
                """;

        AccessControl accessControl = xmlService.deserialize(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(accessControl.getUsers()).extracting(AccessControl.User::getId)
                .containsExactly("root");
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

        AccessControl acl = xmlService.deserialize(
                new ByteArrayInputStream(legacyXml.getBytes(StandardCharsets.UTF_8)));

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

    @Test
    void preservesTheWholeOrionDocument() throws Exception {
        OrionHttpsConfiguration https = new OrionHttpsConfiguration(
                true,
                "localhost",
                8443,
                URI.create("https://localhost:8443"),
                Optional.of(new OrionMaterialReference("https-identity", 1)),
                Optional.empty(),
                OrionHttpsConfiguration.ClientAuthentication.DISABLED,
                List.of(),
                Optional.empty());
        OrionDocument document = new OrionDocument(
                new OrionDocument.SystemConfiguration(
                        ACLUtil.generateDefaultAccessControl("root-password-hash"),
                        Optional.of(https)),
                List.of());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        xmlService.serializeDocument(document, output);
        OrionDocument restored = xmlService.deserializeDocument(
                new ByteArrayInputStream(output.toByteArray()));

        assertThat(restored.system().https()).contains(https);
        assertThat(restored.system().accessControl().getUsers())
                .extracting(AccessControl.User::getId)
                .containsExactly("root");
        assertThat(restored.organizations()).isEmpty();
    }

    private String serialize(AccessControl accessControl) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        xmlService.serialize(accessControl, output);
        return output.toString(StandardCharsets.UTF_8);
    }
}
