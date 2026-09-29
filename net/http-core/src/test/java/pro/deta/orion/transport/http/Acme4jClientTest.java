package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class Acme4jClientTest {
    @Test
    void bindsNewAccountAndReusesItWithoutReplayingOneTimeEab() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> registrations = new CopyOnWriteArrayList<>();
        List<JsonNode> lookups = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Replay-Nonce", "bm9uY2U");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/nonce")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            int status = 200;
            Map<String, ?> response;
            if (path.equals("/directory")) {
                response = Map.of("newNonce", base + "/nonce", "newAccount", base + "/account",
                        "newOrder", base + "/order", "meta", Map.of("externalAccountRequired", true));
            } else {
                JsonNode jws = mapper.readTree(exchange.getRequestBody());
                JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(jws.path("payload").asText()));
                if (payload.path("onlyReturnExisting").asBoolean()) {
                    lookups.add(payload);
                    if (registrations.isEmpty()) {
                        status = 400;
                        response = Map.of("type", "urn:ietf:params:acme:error:accountDoesNotExist",
                                "detail", "Unknown account");
                        exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
                    } else {
                        response = Map.of("status", "valid", "orders", base + "/orders");
                    }
                } else {
                    registrations.add(payload);
                    status = 201;
                    response = Map.of("status", "valid", "orders", base + "/orders");
                }
                exchange.getResponseHeaders().set("Location", base + "/acct/1");
            }
            byte[] bytes = mapper.writeValueAsBytes(response);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            KeyPair account = TestCertificateChain.keyPair();
            Acme4jClient client = new Acme4jClient();
            String hmacKey = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("s".repeat(64).getBytes(StandardCharsets.UTF_8));
            client.createAccount(request(base, account, hmacKey));
            client.createAccount(request(base, account, "used-secret-no-longer-valid"));

            assertThat(lookups).hasSize(2);
            assertThat(registrations).hasSize(1);
            JsonNode registration = registrations.getFirst();
            assertThat(registration.path("termsOfServiceAgreed").asBoolean()).isTrue();
            JsonNode binding = registration.path("externalAccountBinding");
            JsonNode protectedHeader = mapper.readTree(
                    Base64.getUrlDecoder().decode(binding.path("protected").asText()));
            assertThat(protectedHeader.path("kid").asText()).isEqualTo("external-account");
            assertThat(protectedHeader.path("url").asText()).isEqualTo(base + "/account");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("s".repeat(64).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((binding.path("protected").asText() + "."
                    + binding.path("payload").asText()).getBytes(StandardCharsets.US_ASCII));
            assertThat(Base64.getUrlDecoder().decode(binding.path("signature").asText())).isEqualTo(signature);
        } finally {
            server.stop(0);
        }
    }

    private static AcmeCertificateIssueRequest request(String base, KeyPair account, String hmac) {
        return new AcmeCertificateIssueRequest(base + "/directory", "admin@example.test", account, account,
                List.of("example.test"), null, Duration.ofSeconds(5), Duration.ofSeconds(5), true,
                "external-account", hmac.toCharArray());
    }
}
