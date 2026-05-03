package pro.deta.orion.cloudflare;

import org.junit.jupiter.api.Test;
import pro.deta.orion.cloudflare.config.CloudflareConfig;
import pro.deta.orion.cloudflare.model.CloudflareResponse;
import pro.deta.orion.cloudflare.model.DnsRecord;
import pro.deta.orion.cloudflare.model.Zone;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IntegrationCloudflareIT {

    @Test
    public void integrationCloudflareTest() {
        CloudflareConfig config = new CloudflareConfig();
        assumeTrue(config.getApiToken() != null && !config.getApiToken().isBlank(),
                "CLOUDFLARE_API_TOKEN is required for Cloudflare integration tests");

        updateCloudFlareDns(config, "deta.pro", "jump.deta.pro", "A", "18.156.78.214");
        updateCloudFlareDns(config, "deta.pro", "jump.deta.pro", "TXT", "JOPA");
    }

    public boolean updateCloudFlareDns(CloudflareConfig config, String zoneName, String name, String type, String value) {
        CloudflareClientImpl impl = new CloudflareClientImpl(config);
        CloudflareResponse<List<Zone>> cfZones = impl.findZone(zoneName);
        if (cfZones.getResultInfo().getTotalCount() != 1)
            throw new IllegalStateException("Cloudflare API returned non-1 zones: " + cfZones.getResultInfo().getCount() + " / " + cfZones.getResult());
        Zone zone = cfZones.getResult().getFirst();
        CloudflareResponse<List<DnsRecord>> records = impl.listDnsRecords(zone.getId(), Map.of("type", type, "name", name));
        for (DnsRecord record : records.getResult()) {
            impl.deleteDnsRecord(zone.getId(), record.getId());
        }
        DnsRecord record = new DnsRecord();
        record.setType(type);
        record.setName(name);
        record.setContent(value);
        record.setProxied(false);
        record.setTtl(1);
        CloudflareResponse<DnsRecord> result = impl.createDnsRecord(zone.getId(), record);
        return result.isSuccess();
    }
}
