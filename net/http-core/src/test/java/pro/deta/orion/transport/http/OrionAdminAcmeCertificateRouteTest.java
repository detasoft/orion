package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.schema.config.OrionConfiguration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminAcmeCertificateRouteTest {
    @Test
    void returnsTheSavedPublicCertificateChainWithoutPrivateMaterial() throws Exception {
        IssuedAcmeCertificate certificate = certificate("saved.example");
        OrionAdminAcmeCertificateRoute route = route(certificate, Optional.of(certificate));

        OrionHttpResponse response = route.doGet(null);

        assertCertificateResponse(response, "orion-acme-certificate.pem", 2);
    }

    @Test
    void issuesTheCertificateChainWithASafeDomainFilename() throws Exception {
        IssuedAcmeCertificate certificate = certificate("prod.example/../../private.key");
        OrionAdminAcmeCertificateRoute route = route(certificate, Optional.empty());

        OrionHttpResponse response = route.doPost(request(""));

        assertCertificateResponse(
                response,
                "orion-acme-prod.example_.._.._private.key-certificate.pem",
                2);
    }

    @Test
    void reportsAMissingSavedCertificate() throws Exception {
        IssuedAcmeCertificate certificate = certificate("unused.example");
        OrionAdminAcmeCertificateRoute route = route(certificate, Optional.empty());

        OrionHttpResponse response = route.doGet(null);

        assertThat(response.status()).isEqualTo(SC_NOT_FOUND);
        assertThat(response.body()).isNull();
    }

    private static OrionAdminAcmeCertificateRoute route(
            IssuedAcmeCertificate issued,
            Optional<IssuedAcmeCertificate> saved) {
        return new OrionAdminAcmeCertificateRoute(
                new StubAcmeCertificateService(issued, saved),
                new ObjectMapper());
    }

    private static IssuedAcmeCertificate certificate(String domain) throws Exception {
        TestCertificateChain.Authority issuer = TestCertificateChain.root("ACME issuer");
        KeyPair identity = TestCertificateChain.keyPair();
        X509Certificate leaf = TestCertificateChain.leaf(domain, identity, issuer);
        return new IssuedAcmeCertificate(List.of(domain), List.of(leaf, issuer.certificate()));
    }

    private static void assertCertificateResponse(
            OrionHttpResponse response,
            String fileName,
            int certificateCount) {
        assertThat(response.status()).isEqualTo(SC_OK);
        assertThat(response.contentType()).isEqualTo(OrionHttpResponse.PEM_CONTENT_TYPE);
        assertThat(response.headers())
                .containsEntry("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .containsEntry("Cache-Control", "no-store");
        assertThat((String) response.body()).doesNotContain("PRIVATE KEY");
        assertThat(count((String) response.body(), "-----BEGIN CERTIFICATE-----"))
                .isEqualTo(certificateCount);
    }

    private static int count(String value, String needle) {
        int result = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            result++;
            offset += needle.length();
        }
        return result;
    }

    private static HttpServletRequest request(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return HttpServletRequest.class.cast(Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> new ByteArrayServletInputStream(body);
                    case "toString" -> "ACME certificate request";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.toString());
                }));
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] data) {
            input = new ByteArrayInputStream(data);
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }

    private static final class StubAcmeCertificateService extends AcmeCertificateService {
        private final IssuedAcmeCertificate issued;
        private final Optional<IssuedAcmeCertificate> saved;

        private StubAcmeCertificateService(
                IssuedAcmeCertificate issued,
                Optional<IssuedAcmeCertificate> saved) {
            super(
                    new OrionConfiguration(),
                    new OrionDesiredState(),
                    AcmeKeyMaterialCapability.unavailable(),
                    new AcmeCertificateIssuer(null));
            this.issued = issued;
            this.saved = saved;
        }

        @Override
        public IssuedAcmeCertificate issue(IssueRequest request) {
            return issued;
        }

        @Override
        public Optional<IssuedAcmeCertificate> savedCertificate() {
            return saved;
        }
    }
}
