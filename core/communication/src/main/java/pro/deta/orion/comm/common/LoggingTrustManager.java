package pro.deta.orion.comm.common;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

@Slf4j
public class LoggingTrustManager implements X509TrustManager {
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        log.debug("checkClientTrusted: Received certificate chain for {} of length {}", authType, chain.length);
        logChain(chain);
    }

    private static void logChain(X509Certificate[] chain) {
        for (int i = 0; i < chain.length; i++) {
            log.debug("Certificate {} :\n{}", i, chain[i].toString());
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        log.debug("checkServerTrusted: Received certificate chain for {} of length {}", authType, chain.length);
        logChain(chain);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        log.debug("getAcceptedIssuers");
        return new X509Certificate[0];
    }
}
