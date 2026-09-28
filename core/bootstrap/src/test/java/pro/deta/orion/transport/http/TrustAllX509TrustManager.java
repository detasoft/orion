package pro.deta.orion.transport.http;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class TrustAllX509TrustManager implements X509TrustManager {
    public static final X509TrustManager INSTANCE = new TrustAllX509TrustManager();

    private TrustAllX509TrustManager() {
    }

    private static final X509Certificate[] ACCEPTED_ISSUERS = new X509Certificate[0];
    public X509Certificate[] getAcceptedIssuers() { return ACCEPTED_ISSUERS; }
    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
}
