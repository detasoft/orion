package pro.deta.orion.cloudflare;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.deta.orion.cloudflare.config.CloudflareConfig;
import pro.deta.orion.cloudflare.model.CloudflareResponse;
import pro.deta.orion.cloudflare.model.DnsRecord;
import pro.deta.orion.cloudflare.model.Zone;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CloudflareClientImplTest {
    private MockWebServer mockWebServer;
    private CloudflareClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        CloudflareConfig config = new CloudflareConfig();
        config.setBaseUrl(mockWebServer.url("/").toString().replaceAll("/$", ""));
        config.setApiToken("test-token");

        client = new CloudflareClientImpl(config);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void listZones_Success() throws Exception {
        // Prepare test data
        Zone zone = new Zone();
        zone.setId("zone123");
        zone.setName("example.com");

        CloudflareResponse<List<Zone>> response = new CloudflareResponse<>();
        response.setSuccess(true);
        response.setResult(Collections.singletonList(zone));

        // Mock API response
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(response))
                .addHeader("Content-Type", "application/json"));

        // Execute test
        CloudflareResponse<List<Zone>> result = client.listZones(null);

        // Verify request
        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/zones", request.getPath());
        assertEquals("Bearer test-token", request.getHeader("Authorization"));

        // Verify response
        assertTrue(result.isSuccess());
        assertEquals(1, result.getResult().size());
        assertEquals("zone123", result.getResult().get(0).getId());
        assertEquals("example.com", result.getResult().get(0).getName());
    }

    @Test
    void createDnsRecord_Success() throws Exception {
        // Prepare test data
        DnsRecord record = new DnsRecord();
        record.setType("A");
        record.setName("test.example.com");
        record.setContent("192.168.1.1");

        CloudflareResponse<DnsRecord> response = new CloudflareResponse<>();
        response.setSuccess(true);
        response.setResult(record);

        // Mock API response
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(response))
                .addHeader("Content-Type", "application/json"));

        // Execute test
        CloudflareResponse<DnsRecord> result = client.createDnsRecord("zone123", record);

        // Verify request
        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/zones/zone123/dns_records", request.getPath());
        assertEquals("Bearer test-token", request.getHeader("Authorization"));

        // Verify request body
        DnsRecord sentRecord = objectMapper.readValue(request.getBody().readUtf8(), DnsRecord.class);
        assertEquals("A", sentRecord.getType());
        assertEquals("test.example.com", sentRecord.getName());
        assertEquals("192.168.1.1", sentRecord.getContent());

        // Verify response
        assertTrue(result.isSuccess());
        assertEquals("A", result.getResult().getType());
        assertEquals("test.example.com", result.getResult().getName());
        assertEquals("192.168.1.1", result.getResult().getContent());
    }

    @Test
    void apiError_HandledProperly() throws Exception {
        // Prepare error response
        CloudflareResponse<Zone> errorResponse = new CloudflareResponse<>();
        errorResponse.setSuccess(false);
        CloudflareResponse.CloudflareError error = new CloudflareResponse.CloudflareError();
        error.setCode(1001);
        error.setMessage("Invalid request");
        errorResponse.setErrors(Collections.singletonList(error));

        // Mock API response
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody(objectMapper.writeValueAsString(errorResponse))
                .addHeader("Content-Type", "application/json"));

        // Execute test and verify exception
        Exception exception = assertThrows(RuntimeException.class, () -> client.getZone("invalid-zone"));
        assertTrue(exception.getMessage().contains("Failed to execute request"));
    }
}