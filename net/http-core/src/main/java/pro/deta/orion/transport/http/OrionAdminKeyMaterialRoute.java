package pro.deta.orion.transport.http;

import java.util.Set;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.keymaterial.OrionKeyMaterial;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import pro.deta.orion.keymaterial.KeyMaterialPurpose;
import pro.deta.orion.keymaterial.KeyMaterialAlgorithm;
import pro.deta.orion.keymaterial.KeyMaterialDescriptor;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Arrays;

public final class OrionAdminKeyMaterialRoute extends BaseAdminRoute {
    private final OrionKeyMaterial material;
    private final ObjectMapper mapper;

    @Inject
    public OrionAdminKeyMaterialRoute(OrionKeyMaterial material, ObjectMapper mapper) {
        super(OrionAdminPaths.KEY_MATERIAL, OrionHttpRouteDefinition.Method.GET,
                OrionHttpRouteDefinition.Method.POST);
        this.material = material;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) throws IOException {
        try {
            return OrionHttpResponse.ok(Map.of("entries", KeyMaterialInventoryFormatter.format(material)))
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
            if (input.alias() == null || !input.alias().matches("[a-z0-9][a-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("Invalid key material name");
            }
            if (input.purpose() != KeyMaterialPurpose.ACME_ACCOUNT
                    && input.purpose() != KeyMaterialPurpose.TLS_IDENTITY) {
                throw new IllegalArgumentException("Only ACME account and TLS identity keys can be created");
            }
            String alias = input.alias();
            KeyMaterialDescriptor descriptor = new KeyMaterialDescriptor(alias,
                    Set.of(input.purpose()), KeyMaterialAlgorithm.RSA, 1);
            char[] privateKeyPem = input.privateKeyPem();
            material.withModification(material.revision(), modification -> {
                if (material.contains(alias, Set.of())) {
                    throw new IllegalArgumentException("Key material name already exists");
                }
                if (privateKeyPem == null || privateKeyPem.length == 0) {
                    modification.generateKey(descriptor, 3072);
                } else {
                    modification.importKey(descriptor, privateKeyPem);
                }
                modification.apply();
            });
            return OrionHttpResponse.empty(201).withHeader("Cache-Control", "no-store");
        } catch (IllegalArgumentException | JsonProcessingException invalid) {
            return OrionHttpResponse.text(400,
                    "Check the unique key name, purpose and RSA private key PEM or Certbot JSON (2048–8192 bits).");
        } catch (IOException | GeneralSecurityException | IllegalStateException failure) {
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
