package pro.deta.orion.cloudflare.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Represents a DNS Record in Cloudflare
 * @see <a href="https://api.cloudflare.com/#dns-records-for-a-zone-properties">Cloudflare API docs</a>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DnsRecord {
    private String id;
    private String type;
    private String name;
    private String content;
    private Boolean proxiable;
    private Boolean proxied;
    private Integer ttl;
    private Boolean locked;
    
    @JsonProperty("zone_id")
    private String zoneId;
    
    @JsonProperty("zone_name")
    private String zoneName;
    
    @JsonProperty("created_on")
    private OffsetDateTime createdOn;
    
    @JsonProperty("modified_on")
    private OffsetDateTime modifiedOn;
    
    private Map<String, Object> data;
    private Map<String, Object> meta;
    
    private Integer priority;
    
    @JsonProperty("comment")
    private String comment;
    
    private List<String> tags;
}