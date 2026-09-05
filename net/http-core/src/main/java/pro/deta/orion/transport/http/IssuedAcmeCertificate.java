package pro.deta.orion.transport.http;

import java.security.cert.X509Certificate;
import java.util.List;

public record IssuedAcmeCertificate(
        List<String> domains,
        List<X509Certificate> certificateChain) {
    public IssuedAcmeCertificate {
        domains = List.copyOf(domains);
        certificateChain = List.copyOf(certificateChain);
        if (certificateChain.isEmpty()) {
            throw new IllegalArgumentException("Issued ACME certificate chain must not be empty");
        }
    }
}
