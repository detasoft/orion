package pro.deta.orion.transport.http;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.KeyPairUtils;
import pro.deta.orion.config.schema.HttpTransportConfig;
import pro.deta.orion.config.schema.HttpsTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.config.schema.SSLKeyStore;

import java.io.*;
import java.security.KeyPair;
import java.security.Security;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ACMECertificateChallengeTest {
    public static final String ACCOUNT_KEYPAIR = "account.keypair";
    public static final String DOMAIN_KEYPAIR = "domain.keypair";
    //    Session session = new Session("acme://letsencrypt.org");
    Session session = new Session("acme://letsencrypt.org/staging");

    @Test
    public void testChallengeHttp01() {
        startHttp();
    }

    private static JettyHTTPServer startHttp() {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("0.0.0.0", 8080));
        transports.setHttps(new HttpsTransportConfig("0.0.0.0", 8443));
        orionConfiguration.setTransports(transports);

        DispatcherServlet dispatcherServlet = new DispatcherServlet();
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, dispatcherServlet);
        server.onStart();
        return server;
    }

    @Test
    public void createAcmeAccountTest() throws Exception {
        Security.setProperty("crypto.policy", "unlimited");
        Security.addProvider(new BouncyCastleProvider());
        JettyHTTPServer server = startHttp();

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
                System.out.println("now waiting for a status change");
                Status s = auth.waitForCompletion(Duration.ofSeconds(20));
                switch (s) {
                    case VALID -> {
                        log.error("Challenge completed.");
                    }
                    default -> {
                        log.error("Domain challenge cancelled.");
                    }
                }
            }
        }
        KeyPair domainKeyPair = readOrGenerateKeyPair(DOMAIN_KEYPAIR);
        String domainCertificate = "domain.crt";
//        order.waitForCompletion(Duration.ofSeconds(20));
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
                System.out.println("yeeehaa");
            }
            default -> log.error("No certificate created.");
        }

        server.onStop();

//        account.deactivate();
    }

    private KeyPair readOrGenerateKeyPair(String fileName) throws IOException {
        KeyPair kp = null;
        if (new File(fileName).exists())
            kp = KeyPairUtils.readKeyPair(new FileReader(fileName));
        else {
            kp = KeyPairUtils.createKeyPair();
            KeyPairUtils.writeKeyPair(kp, new FileWriter(fileName));
        }
        return kp;
    }

    @Test
    public void startHttpWithGeneratedCertificate() {
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("0.0.0.0", 8080));
        transports.setHttps(new HttpsTransportConfig("0.0.0.0", 8443));
        SSLKeyStore sslKeyStore = new SSLKeyStore();
        sslKeyStore.setPath("domain.crt");
        transports.getHttps().setKsystore(sslKeyStore);
        orionConfiguration.setTransports(transports);

        DispatcherServlet dispatcherServlet = new DispatcherServlet();
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, dispatcherServlet);
        server.onStart();
        server.getDispatcherServlet().register(new PlainOkServlet());
        System.out.println();
    }

}
