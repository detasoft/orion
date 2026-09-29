package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;
import pro.deta.orion.keymaterial.KeyMaterialAdministrationCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Arrays;

public final class OrionAdminKeyMaterialRoute extends BaseAdminRoute {
    private final ConfigurationMaterialCapability material;
    private final KeyMaterialAdministrationCapability administration;
    private final ObjectMapper mapper;

    @Inject
    public OrionAdminKeyMaterialRoute(ConfigurationMaterialCapability material,
            KeyMaterialAdministrationCapability administration, ObjectMapper mapper) {
        super(OrionAdminPaths.KEY_MATERIAL, OrionHttpRouteDefinition.Method.GET,
                OrionHttpRouteDefinition.Method.POST);
        this.material = material;
        this.administration = administration;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) throws IOException {
        try {
            return OrionHttpResponse.ok(Map.of("entries", material.inventory()))
                    .withHeader("Cache-Control", "no-store");
        } catch (GeneralSecurityException failure) {
            throw new IOException("Cannot read key material inventory", failure);
        }
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) {
        byte[] bytes = null;
        CreateKey input = null;
        try {
            if (request.getContentType() == null
                    || !request.getContentType().split(";", 2)[0].equalsIgnoreCase("application/json")) {
                return OrionHttpResponse.empty(415);
            }
            bytes = request.getInputStream().readNBytes(32769);
            if (bytes.length > 32768) return OrionHttpResponse.empty(413);
            input = mapper.readValue(bytes, CreateKey.class);
            if (input == null) throw new IllegalArgumentException("Key settings are required");
            administration.create(input.alias(), input.purpose(), input.privateKeyPem());
            return OrionHttpResponse.empty(201).withHeader("Cache-Control", "no-store");
        } catch (IllegalArgumentException | JsonProcessingException invalid) {
            return OrionHttpResponse.text(400,
                    "Check the unique key name, purpose and RSA private key PEM or Certbot JSON (2048–8192 bits).");
        } catch (IOException | GeneralSecurityException failure) {
            return OrionHttpResponse.text(503, "Could not save key material.");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            if (input != null && input.privateKeyPem() != null) Arrays.fill(input.privateKeyPem(), '\0');
        }
    }

    private record CreateKey(String alias, KeyMaterialPurpose purpose, char[] privateKeyPem) {
        @Override
        public String toString() {
            return "CreateKey[privateKeyPem=<redacted>]";
        }
    }
}
