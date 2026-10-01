package pro.deta.orion.test;

import pro.deta.orion.config.OrionConfigurationEdit;

import pro.deta.orion.config.OrionConfigurationEditor;

import com.fasterxml.jackson.databind.ObjectMapper;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.test.integration.OrionTestRootAccess;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

final class TestBearerTokens {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ROOT_USER = "root";
    private static final KeyPair ROOT_KEY = KeyUtils.generateRSAKeyPair().valueOrFailure("test root key");

    private TestBearerTokens() {
    }

    static String issueRootToken(OrionAccessControlServiceImpl accessControlService,
            OrionConfigurationEditor editor, long expiresInSeconds) {
        AuthenticationResult authentication = accessControlService.authenticateSshUser(
                ROOT_USER, ROOT_KEY.getPublic().getEncoded());
        if (authentication instanceof AuthenticationResult.Failure) {
            enrollRootKey(accessControlService, editor, ROOT_KEY);
        }
        return OrionTestRootAccess.issueToken(accessControlService, ROOT_KEY.getPublic(), expiresInSeconds);
    }

    static void enrollRootKey(OrionAccessControlServiceImpl accessControlService,
            OrionConfigurationEditor editor, KeyPair key) {
        char[] password = accessControlService.plainRootToken(PlainRootTokenAccessForTests.create());
        byte[] credential = new String(password).getBytes(StandardCharsets.UTF_8);
        try (OrionConfigurationEdit edit = editor.edit()) {
            OrionTestRootAccess.enroll(accessControlService, edit, key.getPublic(), credential);
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(credential, (byte) 0);
        }
    }

    static String issueToken(URL tokenUrl, String username, char[] password, long expiresInSeconds)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) tokenUrl.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", basic(username, password));
        connection.setRequestProperty("Content-Type", "application/json");

        byte[] body = OBJECT_MAPPER.writeValueAsBytes(Map.of("expiresInSeconds", expiresInSeconds));
        connection.setFixedLengthStreamingMode(body.length);
        try (var output = connection.getOutputStream()) {
            output.write(body);
        }

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new AssertionError("Token issue failed with HTTP " + responseCode + ": " + responseBody(connection));
        }
        try (InputStream input = connection.getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = OBJECT_MAPPER.readValue(input, Map.class);
            Object token = response.get("token");
            if (!(token instanceof String tokenValue) || tokenValue.isBlank()) {
                throw new AssertionError("Token issue response did not contain a bearer token");
            }
            return tokenValue;
        }
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String basic(String username, char[] password) {
        byte[] credentials = (username + ":" + String.valueOf(password)).getBytes(StandardCharsets.UTF_8);
        try {
            return "Basic " + Base64.getEncoder().encodeToString(credentials);
        } finally {
            Arrays.fill(credentials, (byte) 0);
        }
    }

    private static String responseBody(HttpURLConnection connection) throws IOException {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            input = connection.getInputStream();
        }
        if (input == null) {
            return "";
        }
        try (InputStream responseInput = input) {
            return new String(responseInput.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
