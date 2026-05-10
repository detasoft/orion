package pro.deta.orion.transport.http;

import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;

import java.io.IOException;
import java.io.Writer;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class Acme4jClient implements AcmeCertificateIssuer.AcmeClient {
    @Override
    public AcmeCertificateIssuer.AcmeAccount createAccount(AcmeCertificateIssueRequest request) throws Exception {
        Session session = new Session(request.directoryUrl());
        AccountBuilder builder = new AccountBuilder()
                .addEmail(request.accountEmail())
                .useKeyPair(request.accountKeyPair());
        if (request.agreeToTermsOfService()) {
            builder.agreeToTermsOfService();
        }
        return new Acme4jAccount(builder.create(session));
    }

    private record Acme4jAccount(Account account) implements AcmeCertificateIssuer.AcmeAccount {
        @Override
        public AcmeCertificateIssuer.AcmeOrder createOrder(List<String> domains) throws Exception {
            return new Acme4jOrder(account.newOrder().domains(domains).create());
        }
    }

    private record Acme4jOrder(Order order) implements AcmeCertificateIssuer.AcmeOrder {
        @Override
        public List<AcmeCertificateIssuer.AcmeAuthorization> authorizations() {
            List<AcmeCertificateIssuer.AcmeAuthorization> result = new ArrayList<>();
            for (Authorization authorization : order.getAuthorizations()) {
                result.add(new Acme4jAuthorization(authorization));
            }
            return result;
        }

        @Override
        public Status waitUntilReady(Duration timeout) throws Exception {
            return order.waitUntilReady(timeout);
        }

        @Override
        public void execute(KeyPair domainKeyPair, String organization) throws Exception {
            order.execute(domainKeyPair, csr -> {
                if (organization != null && !organization.isBlank()) {
                    csr.setOrganization(organization);
                }
            });
        }

        @Override
        public Status waitForCompletion(Duration timeout) throws Exception {
            return order.waitForCompletion(timeout);
        }

        @Override
        public void writeCertificate(Writer writer) throws IOException {
            order.getCertificate().writeCertificate(writer);
        }
    }

    private record Acme4jAuthorization(Authorization authorization) implements AcmeCertificateIssuer.AcmeAuthorization {
        @Override
        public String identifier() {
            return authorization.getIdentifier().toString();
        }

        @Override
        public Status status() {
            return authorization.getStatus();
        }

        @Override
        public AcmeCertificateIssuer.AcmeHttp01Challenge http01Challenge() {
            Http01Challenge challenge = authorization.findChallenge(Http01Challenge.class).orElse(null);
            if (challenge == null) {
                return null;
            }
            return new Acme4jHttp01Challenge(challenge);
        }

        @Override
        public Status waitForCompletion(Duration timeout) throws Exception {
            return authorization.waitForCompletion(timeout);
        }
    }

    private record Acme4jHttp01Challenge(Http01Challenge challenge) implements AcmeCertificateIssuer.AcmeHttp01Challenge {
        @Override
        public String token() {
            return challenge.getToken();
        }

        @Override
        public String authorization() {
            return challenge.getAuthorization();
        }

        @Override
        public void trigger() throws Exception {
            challenge.trigger();
        }
    }
}
