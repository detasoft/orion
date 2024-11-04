package pro.deta.orion.cloudflare.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Represents a DNS Zone in Cloudflare
 * @see <a href="https://api.cloudflare.com/#zone-properties">Cloudflare API docs</a>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Zone {
    private String id;
    private String name;
    private String status;
    private boolean paused;
    private String type;
    
    @JsonProperty("development_mode")
    private Integer developmentMode;
    
    @JsonProperty("name_servers")
    private List<String> nameServers;
    
    @JsonProperty("original_name_servers")
    private List<String> originalNameServers;
    
    @JsonProperty("original_registrar")
    private String originalRegistrar;
    
    @JsonProperty("original_dnshost")
    private String originalDnshost;
    
    @JsonProperty("modified_on")
    private OffsetDateTime modifiedOn;
    
    @JsonProperty("created_on")
    private OffsetDateTime createdOn;
    
    @JsonProperty("activated_on")
    private OffsetDateTime activatedOn;
    
    @JsonProperty("account")
    private Account account;
    
    @JsonProperty("permissions")
    private List<String> permissions;
    
    @JsonProperty("plan")
    private Plan plan;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Account {
        private String id;
        private String name;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Plan {
        private String id;
        private String name;
        private String currency;
        private Double price;
    }
}