package pro.deta.orion.cloudflare;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import pro.deta.orion.cloudflare.config.CloudflareConfig;
import pro.deta.orion.cloudflare.model.CloudflareResponse;
import pro.deta.orion.cloudflare.model.DnsRecord;
import pro.deta.orion.cloudflare.model.Zone;

import com.fasterxml.jackson.core.JsonProcessingException;
import pro.deta.orion.util.stream.TeeInputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CloudflareClientImpl implements CloudflareClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String AUTH_HEADER = "Authorization";
    private static final String AUTH_BEARER = "Bearer ";

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final CloudflareConfig config;

    public CloudflareClientImpl(CloudflareConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        this.client = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectionTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    @Override
    public CloudflareResponse<List<Zone>> listZones(Map<String, String> params) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(config.getBaseUrl() + "/zones").newBuilder();
        if (params != null) {
            params.forEach(urlBuilder::addQueryParameter);
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .get()
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<List<Zone>>>() {});
    }

    @Override
    public CloudflareResponse<Zone> getZone(String zoneId) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones/" + zoneId)
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .get()
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<Zone>>() {});
    }

    private RequestBody createJsonBody(Object obj) {
        try {
            return RequestBody.create(objectMapper.writeValueAsString(obj), JSON);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    @Override
    public CloudflareResponse<Zone> createZone(Zone zone) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones")
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .post(createJsonBody(zone))
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<Zone>>() {});
    }

    @Override
    public CloudflareResponse<Zone> updateZone(String zoneId, Zone zone) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones/" + zoneId)
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .patch(createJsonBody(zone))
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<Zone>>() {});
    }

    @Override
    public CloudflareResponse<Zone> deleteZone(String zoneId) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones/" + zoneId)
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .delete()
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<Zone>>() {});
    }

    @Override
    public CloudflareResponse<List<DnsRecord>> listDnsRecords(String zoneId, Map<String, String> params) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(config.getBaseUrl() + "/zones/" + zoneId + "/dns_records").newBuilder();
        if (params != null) {
            params.forEach(urlBuilder::addQueryParameter);
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .get()
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<List<DnsRecord>>>() {});
    }

    @Override
    public CloudflareResponse<DnsRecord> getDnsRecord(String zoneId, String recordId) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones/" + zoneId + "/dns_records/" + recordId)
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .get()
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<DnsRecord>>() {});
    }

    @Override
    public CloudflareResponse<DnsRecord> createDnsRecord(String zoneId, DnsRecord record) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones/" + zoneId + "/dns_records")
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .post(createJsonBody(record))
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<DnsRecord>>() {});
    }

    @Override
    public CloudflareResponse<DnsRecord> updateDnsRecord(String zoneId, String recordId, DnsRecord record) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones/" + zoneId + "/dns_records/" + recordId)
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .put(createJsonBody(record))
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<DnsRecord>>() {});
    }

    @Override
    public CloudflareResponse<DnsRecord> deleteDnsRecord(String zoneId, String recordId) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones/" + zoneId + "/dns_records/" + recordId)
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .delete()
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<DnsRecord>>() {});
    }

    @Override
    public CloudflareResponse<List<Zone>> findZone(String zoneName) {
        Request request = new Request.Builder()
                .url(config.getBaseUrl() + "/zones?name=" + zoneName)
                .header(AUTH_HEADER, AUTH_BEARER + config.getApiToken())
                .build();

        return executeRequest(request, new TypeReference<CloudflareResponse<List<Zone>>>() {});
    }

    private <T> T executeRequest(Request request, TypeReference<T> typeReference) {
        ByteArrayOutputStream arrayOutputStream = new ByteArrayOutputStream();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalStateException(String.format("No response from Cloudflare: %s", response.request().url()));
            }

            TeeInputStream teeInputStream = new TeeInputStream(body.source().inputStream(), arrayOutputStream);
            if (!response.isSuccessful()) {
                log.error("Request failed with code {}, response: {}", response.code(), new String(teeInputStream.readAllBytes()));
                throw new IOException("Request failed with code: " + response.code());
            }

            return objectMapper.readValue(teeInputStream, typeReference);
        } catch (IOException e) {
            throw new RuntimeException("Failed to execute/parse request " + arrayOutputStream, e);
        }
    }
}
