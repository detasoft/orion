package pro.deta.orion.cloudflare;

import pro.deta.orion.cloudflare.model.CloudflareResponse;
import pro.deta.orion.cloudflare.model.DnsRecord;
import pro.deta.orion.cloudflare.model.Zone;

import java.util.List;
import java.util.Map;

/**
 * Client interface for interacting with Cloudflare's DNS Zone API
 */
public interface CloudflareClient {
    /**
     * List zones in the account
     *
     * @param params Optional query parameters (name, status, page, per_page, etc.)
     * @return Response containing list of zones
     */
    CloudflareResponse<List<Zone>> listZones(Map<String, String> params);

    /**
     * Get zone details by ID
     *
     * @param zoneId Zone identifier
     * @return Response containing zone details
     */
    CloudflareResponse<Zone> getZone(String zoneId);

    /**
     * Create a new zone
     *
     * @param zone Zone to create
     * @return Response containing created zone
     */
    CloudflareResponse<Zone> createZone(Zone zone);

    /**
     * Update an existing zone
     *
     * @param zoneId Zone identifier
     * @param zone   Zone data to update
     * @return Response containing updated zone
     */
    CloudflareResponse<Zone> updateZone(String zoneId, Zone zone);

    /**
     * Delete a zone
     *
     * @param zoneId Zone identifier
     * @return Response indicating success
     */
    CloudflareResponse<Zone> deleteZone(String zoneId);

    /**
     * List DNS records in a zone
     *
     * @param zoneId Zone identifier
     * @param params Optional query parameters (type, name, content, page, per_page, etc.)
     * @return Response containing list of DNS records
     */
    CloudflareResponse<List<DnsRecord>> listDnsRecords(String zoneId, Map<String, String> params);

    /**
     * Get DNS record details
     *
     * @param zoneId     Zone identifier
     * @param recordId   DNS record identifier
     * @return Response containing DNS record details
     */
    CloudflareResponse<DnsRecord> getDnsRecord(String zoneId, String recordId);

    /**
     * Create a new DNS record in a zone
     *
     * @param zoneId Zone identifier
     * @param record DNS record to create
     * @return Response containing created DNS record
     */
    CloudflareResponse<DnsRecord> createDnsRecord(String zoneId, DnsRecord record);

    /**
     * Update an existing DNS record
     *
     * @param zoneId   Zone identifier
     * @param recordId DNS record identifier
     * @param record   DNS record data to update
     * @return Response containing updated DNS record
     */
    CloudflareResponse<DnsRecord> updateDnsRecord(String zoneId, String recordId, DnsRecord record);

    /**
     * Delete a DNS record
     *
     * @param zoneId   Zone identifier
     * @param recordId DNS record identifier
     * @return Response indicating success
     */
    CloudflareResponse<DnsRecord> deleteDnsRecord(String zoneId, String recordId);

    /**
     * Finds a zone by its name
     *
     * @param zoneName Zone name
     * @return Response indicating success
     */
    CloudflareResponse<List<Zone>> findZone(String zoneName);
}