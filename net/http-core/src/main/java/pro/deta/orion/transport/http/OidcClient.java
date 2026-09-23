package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import pro.deta.orion.schema.orion.OidcProvider;
import pro.deta.orion.schema.orion.OrganizationInvitation;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** OIDC authorization-code client; validates provider metadata and signed ID tokens with Nimbus. */
final class OidcClient {
    private final ObjectMapper mapper;

    OidcClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Metadata discover(OidcProvider provider) throws Exception {
        String issuer = provider.issuer().toString();
        URI discovery = URI.create(issuer.replaceAll("/$", "") + "/.well-known/openid-configuration");
        JsonNode document = request(discovery, null, null);
        if (!issuer.equals(document.path("issuer").asText())) {
            throw new IOException("Provider issuer mismatch");
        }
        boolean basic = !document.has("token_endpoint_auth_methods_supported");
        boolean post = false;
        for (JsonNode method : document.path("token_endpoint_auth_methods_supported")) {
            basic |= method.asText().equals("client_secret_basic");
            post |= method.asText().equals("client_secret_post");
        }
        if (!basic && !post) {
            throw new IOException("Provider requires unsupported client authentication");
        }
        return new Metadata(endpoint(document.path("authorization_endpoint").asText()),
                endpoint(document.path("token_endpoint").asText()), endpoint(document.path("jwks_uri").asText()),
                basic);
    }

    URI authorize(OidcProvider provider, Metadata metadata, URI callback, String state, String nonce,
            String verifier) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", provider.clientId());
        parameters.put("redirect_uri", callback.toString());
        parameters.put("scope", "openid email profile");
        if (provider.reauthenticationTimeoutSeconds() > 0) parameters.put("max_age", "0");
        parameters.put("state", state);
        parameters.put("nonce", nonce);
        parameters.put("code_challenge", challenge);
        parameters.put("code_challenge_method", "S256");
        return URI.create(metadata.authorization() + (metadata.authorization().getRawQuery() == null ? "?" : "&")
                + form(parameters));
    }

    Identity exchange(OidcProvider provider, Metadata metadata, URI callback, String code, String nonce,
            String verifier, char[] secret, long authenticationNotBefore) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("grant_type", "authorization_code");
        parameters.put("code", code);
        parameters.put("redirect_uri", callback.toString());
        parameters.put("code_verifier", verifier);
        String authorization = null;
        if (metadata.basic()) {
            authorization = "Basic " + Base64.getEncoder().encodeToString(
                    (encode(provider.clientId()) + ":" + encode(new String(secret)))
                            .getBytes(StandardCharsets.UTF_8));
        } else {
            parameters.put("client_id", provider.clientId());
            parameters.put("client_secret", new String(secret));
        }
        JsonNode response = request(metadata.token(), form(parameters), authorization);
        String token = response.path("id_token").asText();
        JWKSet keys = JWKSet.parse(request(metadata.keys(), null, null).toString());
        IDTokenValidator validator = new IDTokenValidator(new Issuer(provider.issuer().toString()),
                new ClientID(provider.clientId()), JWSAlgorithm.RS256, keys);
        IDTokenClaimsSet claims = validator.validate(JWTParser.parse(token), new Nonce(nonce));
        JsonNode values = mapper.readTree(claims.toJSONObject().toJSONString());
        if (!values.path("email_verified").isBoolean() || !values.path("email_verified").booleanValue()) {
            throw new IOException("Verified email is required");
        }
        long authenticatedAt = 0;
        if (provider.reauthenticationTimeoutSeconds() > 0) {
            JsonNode value = values.path("auth_time");
            if (!value.isIntegralNumber() || !value.canConvertToLong()
                    || value.longValue() < authenticationNotBefore - 60
                    || value.longValue() > Instant.now().getEpochSecond() + 60) {
                throw new IOException("Fresh provider authentication is required");
            }
            authenticatedAt = value.longValue();
        }
        String subject = claims.getSubject().getValue();
        if (subject.isBlank() || subject.length() > 255) {
            throw new IOException("Invalid OIDC subject");
        }
        return new Identity(subject, OrganizationInvitation.normalizeEmail(values.path("email").asText()),
                values.path("given_name").asText(""), values.path("family_name").asText(""), authenticatedAt);
    }

    private JsonNode request(URI endpoint, String body, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                if (authorization != null) {
                    connection.setRequestProperty("Authorization", authorization);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            if (connection.getResponseCode() != 200) {
                throw new IOException("Provider request failed");
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) {
                    throw new IOException("Provider response is too large");
                }
                return mapper.readTree(bytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static URI endpoint(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("OIDC endpoints require HTTPS");
        }
        return uri;
    }

    private static String form(Map<String, String> values) {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!form.isEmpty()) {
                form.append('&');
            }
            form.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return form.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Metadata(URI authorization, URI token, URI keys, boolean basic) { }
    record Identity(String subject, String email, String first, String last, long authenticatedAt) { }
}
