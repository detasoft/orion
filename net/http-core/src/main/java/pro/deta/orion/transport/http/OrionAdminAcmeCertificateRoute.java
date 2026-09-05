package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.security.cert.X509Certificate;
import java.util.List;

import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminAcmeCertificateRoute extends BaseAdminRoute {
    private final AcmeCertificateService certificateService;
    private final ObjectMapper objectMapper;

    @Inject
    public OrionAdminAcmeCertificateRoute(
            AcmeCertificateService certificateService,
            ObjectMapper objectMapper) {
        super(OrionAdminPaths.ACME_CERTIFICATE, "GET", "POST");
        this.certificateService = certificateService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) throws IOException {
        return certificateService.savedCertificate()
                .map(certificate -> certificateResponse(
                        certificateChainPem(certificate.certificateChain()),
                        "orion-acme-certificate.pem"))
                .orElseGet(() -> OrionHttpResponse.empty(SC_NOT_FOUND));
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        AcmeCertificateService.IssueRequest request = issueRequest(req);
        IssuedAcmeCertificate certificate = certificateService.issue(request);
        return certificateResponse(
                certificateChainPem(certificate.certificateChain()),
                fileNameFor(certificate));
    }

    private AcmeCertificateService.IssueRequest issueRequest(HttpServletRequest req) throws IOException {
        byte[] body = req.getInputStream().readAllBytes();
        if (body.length == 0) {
            return AcmeCertificateService.IssueRequest.EMPTY;
        }
        return objectMapper.readValue(body, AcmeCertificateService.IssueRequest.class);
    }

    private static OrionHttpResponse certificateResponse(String pem, String fileName) {
        return OrionHttpResponse.pem(SC_OK, pem)
                .withHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .withHeader("Cache-Control", "no-store");
    }

    private static String fileNameFor(IssuedAcmeCertificate certificate) {
        String domain = "certificate";
        if (!certificate.domains().isEmpty()) {
            domain = certificate.domains().getFirst();
        }
        return "orion-acme-" + safeFileName(domain) + "-certificate.pem";
    }

    private static String certificateChainPem(List<X509Certificate> certificateChain) {
        try {
            StringWriter output = new StringWriter();
            try (JcaPEMWriter writer = new JcaPEMWriter(output)) {
                for (X509Certificate certificate : certificateChain) {
                    writer.writeObject(certificate);
                }
            }
            return output.toString();
        } catch (IOException failure) {
            throw new AcmeCertificateIssueException("Cannot encode ACME certificate chain", failure);
        }
    }

    private static String safeFileName(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (Character.isLetterOrDigit(next) || next == '.' || next == '_' || next == '-') {
                result.append(next);
            } else {
                result.append('_');
            }
        }
        if (result.isEmpty()) {
            return "certificate";
        }
        return result.toString();
    }
}
