package pro.deta.orion.transport.http;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.util.KeyPairUtils;
import pro.deta.orion.config.schema.HttpTransportConfig;
import pro.deta.orion.config.schema.HttpsTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.util.NetworkUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyPair;
import java.security.Security;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class ACMECertificateChallengeTest {
    private static final String ACCOUNT_KEYPAIR = "account.keypair";
    private static final String DOMAIN_KEYPAIR = "domain.keypair";

    @Test
    public void testChallengeHttp01() throws Exception {
        JettyHTTPServer server = startHttp();

        try {
            ChallengeAuthorizationServlet challengeServlet = new ChallengeAuthorizationServlet(
                    "test-token",
                    "test-authorization");
            server.getDispatcherServlet().register(challengeServlet);

            HttpURLConnection connection = get(server.relativiseHttp(challengeServlet.servletPath()));

            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(new String(connection.getInputStream().readAllBytes()).trim()).isEqualTo("test-authorization");
            assertThat(challengeServlet.getLatch().await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            server.onStop();
        }
    }

    private static JettyHTTPServer startHttp() throws IOException {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", NetworkUtils.findAvailablePort()));

        HttpsTransportConfig httpsTransportConfig = new HttpsTransportConfig("localhost", NetworkUtils.findAvailablePort());
        httpsTransportConfig.setEnabled(false);
        transports.setHttps(httpsTransportConfig);

        orionConfiguration.setTransports(transports);

        DispatcherServlet dispatcherServlet = new DispatcherServlet();
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, dispatcherServlet);
        server.onStart();
        return server;
    }

    @Test
    @Disabled("Manual integration test: requires ams.deta.pro to resolve to this machine and public port 80 access")
    public void createAcmeAccountManually() throws Exception {
        Security.setProperty("crypto.policy", "unlimited");
        Security.addProvider(new BouncyCastleProvider());
        JettyHTTPServer server = startHttp();

        try {
            Session session = new Session("acme://letsencrypt.org/staging");
            KeyPair accountKeyPair = readOrGenerateKeyPair(ACCOUNT_KEYPAIR);
            Account account = new AccountBuilder()
                    .addEmail("tenteaday@gmail.com")
                    .useKeyPair(accountKeyPair)
                    .agreeToTermsOfService()
                    .create(session);
            Order order = account.newOrder().domains("ams.deta.pro")
                    .create();
            for (Authorization auth : order.getAuthorizations()) {
                if (auth.getStatus() == Status.PENDING) {
                    log.info("Authorizing " + auth.getIdentifier());
                    Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
                            .orElse(null);
                    if (challenge == null) {
                        continue;
                    }
                    ChallengeAuthorizationServlet cas = new ChallengeAuthorizationServlet(challenge.getToken(), challenge.getAuthorization());
                    server.getDispatcherServlet().register(cas);
                    challenge.trigger();
                    cas.getLatch().await(5, TimeUnit.SECONDS);
                    log.info("Waiting for authorization status change");
                    Status s = auth.waitForCompletion(Duration.ofSeconds(20));
                    switch (s) {
                        case VALID -> log.info("Challenge completed.");
                        default -> throw new IllegalStateException("ACME authorization failed with status " + s);
                    }
                }
            }
            KeyPair domainKeyPair = readOrGenerateKeyPair(DOMAIN_KEYPAIR);
            String domainCertificate = "domain.crt";
            order.execute(domainKeyPair, csr -> {
                csr.setOrganization("DETA PRO");
            });
            order.waitForCompletion(Duration.ofSeconds(20));
            switch (order.getStatus()) {
                case VALID -> {
                    Certificate cert = order.getCertificate();
                    try (FileWriter fw = new FileWriter(domainCertificate)) {
                        cert.writeCertificate(fw);
                        KeyPairUtils.writeKeyPair(domainKeyPair, fw);
                    }
                    log.info("Certificate created.");
                }
                default -> log.error("No certificate created.");
            }
        } finally {
            server.onStop();
        }
    }

    private KeyPair readOrGenerateKeyPair(String fileName) throws IOException {
        File keyFile = new File(fileName);
        if (keyFile.exists()) {
            try (FileReader reader = new FileReader(keyFile)) {
                return KeyPairUtils.readKeyPair(reader);
            }
        }

        KeyPair keyPair = KeyPairUtils.createKeyPair();
        try (FileWriter writer = new FileWriter(keyFile)) {
            KeyPairUtils.writeKeyPair(keyPair, writer);
        }
        return keyPair;
    }

    @Test
    public void startHttpWithGeneratedCertificate() throws Exception {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", NetworkUtils.findAvailablePort()));
        transports.setHttps(new HttpsTransportConfig("localhost", NetworkUtils.findAvailablePort()));
        orionConfiguration.setTransports(transports);

        DispatcherServlet dispatcherServlet = new DispatcherServlet();
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, dispatcherServlet);
        server.onStart();
        server.getDispatcherServlet().register(new PlainOkServlet());

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{TrustAllX509TrustManager.INSTANCE}, new java.security.SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            HttpURLConnection connection = get(server.relativiseHttp("/ok"));
            assertThat(connection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);

            HttpsURLConnection httpsConnection = (HttpsURLConnection) get(server.relativiseHttps("/ok"));
            httpsConnection.setSSLSocketFactory(sslSocketFactory);
            assertThat(httpsConnection.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);
        } finally {
            server.onStop();
        }
    }

    private static HttpURLConnection get(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        return connection;
    }

}
