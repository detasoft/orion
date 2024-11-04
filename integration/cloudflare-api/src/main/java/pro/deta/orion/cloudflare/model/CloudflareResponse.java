package pro.deta.orion.cloudflare.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Generic wrapper for Cloudflare API responses
 * @see <a href="https://api.cloudflare.com/#getting-started-responses">Cloudflare API docs</a>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudflareResponse<T> {
    private boolean success;
    private List<CloudflareError> errors;
    private List<String> messages;
    
    @JsonProperty("result")
    private T result;
    
    @JsonProperty("result_info")
    private ResultInfo resultInfo;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResultInfo {
        private Integer page;
        
        @JsonProperty("per_page")
        private Integer perPage;
        
        @JsonProperty("total_pages")
        private Integer totalPages;
        
        private Integer count;
        
        @JsonProperty("total_count")
        private Integer totalCount;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CloudflareError {
        private Integer code;
        private String message;
        
        @JsonProperty("error_chain")
        private List<CloudflareError> errorChain;
    }
}