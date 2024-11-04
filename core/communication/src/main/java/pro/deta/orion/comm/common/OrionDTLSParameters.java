package pro.deta.orion.comm.common;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.security.SecureRandom;

@AllArgsConstructor
@Slf4j
public class OrionDTLSParameters {
    public static final OrionDTLSParameters TEST_ONLY_PARAMETERS = new OrionDTLSParameters(new byte[]{1, 2, 3, 4, 5}) {
        @Override
        public boolean isTest() {
            return true;
        }
    };

    private final KeyManager[] keyManagers;
    private final TrustManager[] trustManagers;
    private final SecureRandom random;

    public OrionDTLSParameters(byte[] fixedSeed) {
        keyManagers = CertificateUtil.getKeyManager();
        trustManagers = new TrustManager[]{new LoggingTrustManager()};
        random = new SecureRandom(fixedSeed);
    }

    public SSLContext initContext() {
        try {
            if (isTest()) {
                log.error("!!!!! TEST MODE ACTIVE: DO NOT USE IN PRODUCTION !!!!!");
            }
            SSLContext context = SSLContext.getInstance("DTLS");
            context.init(keyManagers, trustManagers, random);
            return context;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isTest() {
        return false;
    }
}
