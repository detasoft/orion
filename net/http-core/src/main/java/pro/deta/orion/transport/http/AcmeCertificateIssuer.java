package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;

@Singleton
public class AcmeCertificateIssuer {
    private final AcmeHttpChallengeService challengeService;
    private final AcmeClient acmeClient;

    @Inject
    public AcmeCertificateIssuer(AcmeHttpChallengeService challengeService) {
        this(challengeService, new Acme4jClient());
    }

    AcmeCertificateIssuer(AcmeHttpChallengeService challengeService, AcmeClient acmeClient) {
        this.challengeService = challengeService;
        this.acmeClient = acmeClient;
    }

    public IssuedAcmeCertificate issue(AcmeCertificateIssueRequest request) {
        try {
            AcmeAccount account = acmeClient.createAccount(request);
            AcmeOrder order = account.createOrder(request.domains());
            authorize(order.authorizations(), request.authorizationTimeout());
            finalizeOrder(order, request);
            return new IssuedAcmeCertificate(
                    request.domains(),
                    certificateChainPem(order),
                    privateKeyPem(request.domainKeyPair()));
        } catch (AcmeCertificateIssueException e) {
            throw e;
        } catch (Exception e) {
            throw new AcmeCertificateIssueException("ACME certificate issue failed", e);
        }
    }

    private void authorize(List<AcmeAuthorization> authorizations, Duration timeout) throws Exception {
        for (AcmeAuthorization authorization : authorizations) {
            Status status = authorization.status();
            if (status == Status.VALID) {
                continue;
            }
            if (status != Status.PENDING) {
                throw new AcmeCertificateIssueException(
                        "ACME authorization for " + authorization.identifier() + " is " + status);
            }
            AcmeHttp01Challenge challenge = authorization.http01Challenge();
            if (challenge == null) {
                throw new AcmeCertificateIssueException(
                        "ACME authorization for " + authorization.identifier() + " has no http-01 challenge");
            }
            answerHttp01Challenge(authorization, challenge, timeout);
        }
    }

    private void answerHttp01Challenge(
            AcmeAuthorization authorization,
            AcmeHttp01Challenge challenge,
            Duration timeout) throws Exception {
        String token = challenge.token();
        challengeService.registerChallenge(token, challenge.authorization());
        try {
            challenge.trigger();
            Status status = authorization.waitForCompletion(timeout);
            if (status != Status.VALID) {
                throw new AcmeCertificateIssueException(
                        "ACME authorization for " + authorization.identifier() + " finished with " + status);
            }
        } finally {
            challengeService.removeChallenge(token);
        }
    }

    private static void finalizeOrder(AcmeOrder order, AcmeCertificateIssueRequest request) throws Exception {
        Status readyStatus = order.waitUntilReady(request.orderTimeout());
        if (readyStatus == Status.VALID) {
            return;
        }
        if (readyStatus != Status.READY) {
            throw new AcmeCertificateIssueException("ACME order is not ready: " + readyStatus);
        }

        order.execute(request.domainKeyPair(), request.organization());
        Status finalStatus = order.waitForCompletion(request.orderTimeout());
        if (finalStatus != Status.VALID) {
            throw new AcmeCertificateIssueException("ACME order finished with " + finalStatus);
        }
    }

    private static String certificateChainPem(AcmeOrder order) throws IOException {
        StringWriter writer = new StringWriter();
        order.writeCertificate(writer);
        return writer.toString();
    }

    private static String privateKeyPem(KeyPair domainKeyPair) throws IOException {
        StringWriter writer = new StringWriter();
        KeyPairUtils.writeKeyPair(domainKeyPair, writer);
        return writer.toString();
    }

    interface AcmeClient {
        AcmeAccount createAccount(AcmeCertificateIssueRequest request) throws Exception;
    }

    interface AcmeAccount {
        AcmeOrder createOrder(List<String> domains) throws Exception;
    }

    interface AcmeOrder {
        List<AcmeAuthorization> authorizations() throws Exception;

        Status waitUntilReady(Duration timeout) throws Exception;

        void execute(KeyPair domainKeyPair, String organization) throws Exception;

        Status waitForCompletion(Duration timeout) throws Exception;

        void writeCertificate(Writer writer) throws IOException;
    }

    interface AcmeAuthorization {
        String identifier();

        Status status();

        AcmeHttp01Challenge http01Challenge();

        Status waitForCompletion(Duration timeout) throws Exception;
    }

    interface AcmeHttp01Challenge {
        String token();

        String authorization();

        void trigger() throws Exception;
    }
}
