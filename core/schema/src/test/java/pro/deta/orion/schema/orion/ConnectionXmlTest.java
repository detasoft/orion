package pro.deta.orion.schema.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.v2.Connection;
import pro.deta.orion.schema.orion.v2.GitCredentialKind;
import pro.deta.orion.schema.orion.v2.OrionDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionXmlTest {
    @Test
    void roundTripsScopedConnectionsAndStorageAndPreservesThemWhenAclChanges() throws Exception {
        OrionDocument document = read(xml("organization", "archive"));
        assertThat(document.system().connections()).hasSize(1);
        Connection.S3 connection = (Connection.S3) document.organizations().getFirst().connections().getFirst();
        assertThat(connection.region()).isEqualTo("us-east-1");
        assertThat(connection.secretKey()).contains("key");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OrionXml.write(document.replaceAccessControl(new AccessControl()), output);
        assertThat(read(output.toString(StandardCharsets.UTF_8))).isEqualTo(document);
        assertThat(document.organizations().getFirst().teams().getFirst().repositories().getFirst().storage())
                .isPresent();
    }

    @Test
    void resolvesOnlyTheExplicitScopeAndRejectsMissingOrWrongTypeConnections() throws Exception {
        assertThat(read(xml("system", "archive")).system().connections()).hasSize(1);
        assertThatThrownBy(() -> read(xml("organization", "missing"))).hasMessageContaining("Unknown connection");
        assertThatThrownBy(() -> read(xml("other", "archive"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> read(xml("organization", "archive").replace(
                "<s3 name=\"archive\"><accessKeyId>id</accessKeyId><secretKey>key</secretKey></s3>",
                "<ssh name=\"archive\"><host>git.example</host><credentialKind>PRIVATE_KEY</credentialKind>"
                        + "<secret>key</secret></ssh>")))
                .hasMessageContaining("requires an S3 connection");
    }

    @Test
    void rejectsDuplicateConnectionNamesAndSecretsFromAnotherOwner() {
        String xml = xml("organization", "archive");
        assertThatThrownBy(() -> read(xml.replace("</connections>", "<s3 name=\"archive\"/></connections>")))
                .hasMessageContaining("duplicate connection");
        assertThatThrownBy(() -> read(xml.replace("<secretKey>key</secretKey>", "<secretKey>system-key</secretKey>")))
                .hasMessageContaining("secret is unavailable");
    }

    @Test
    void sshConnectionPreservesIpv6AuthorityAndEncodedRepositoryPath() {
        java.net.URI upstream = java.net.URI.create("ssh://git@[2001:db8::1]:2222/team/a%20b.git");
        Connection.Ssh connection = Connection.Ssh.fromUpstream("remote", upstream,
                GitCredentialKind.PRIVATE_KEY,
                java.util.Optional.of("key"), java.util.Set.of());
        assertThat(connection.upstream(upstream.getRawPath())).isEqualTo(upstream);
    }

    private static OrionDocument read(String xml) throws Exception {
        return OrionXml.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String xml(String scope, String name) {
        return """
                <orion schemaVersion="2"><system><accessControl><users/><roles/><grants/></accessControl>
                <connections><s3 name="archive"><region>eu-west-1</region></s3></connections></system>
                <organizations><organization id="acme"><teams><team id="dev"><repositories>
                <repository id="repo"><storage><s3 location="s3://bucket/prefix">
                <connection scope="%s" name="%s"/></s3></storage></repository>
                </repositories></team></teams><secrets><secret id="key"><envelope>opaque</envelope></secret></secrets>
                <connections><s3 name="archive"><accessKeyId>id</accessKeyId><secretKey>key</secretKey></s3></connections>
                </organization></organizations></orion>
                """.formatted(scope, name);
    }
}
