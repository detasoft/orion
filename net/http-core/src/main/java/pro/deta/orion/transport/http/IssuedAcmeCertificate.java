package pro.deta.orion.transport.http;

import java.util.List;

public record IssuedAcmeCertificate(
        List<String> domains,
        String certificateChainPem,
        String privateKeyPem) {
    public IssuedAcmeCertificate {
        domains = List.copyOf(domains);
    }

    public String nginxPem() {
        if (certificateChainPem.endsWith("\n")) {
            return certificateChainPem + privateKeyPem;
        }
        return certificateChainPem + "\n" + privateKeyPem;
    }
}
