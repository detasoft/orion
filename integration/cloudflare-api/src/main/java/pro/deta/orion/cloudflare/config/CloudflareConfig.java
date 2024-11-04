package pro.deta.orion.cloudflare.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration properties for Cloudflare API client
 */
@Data
@NoArgsConstructor
public class CloudflareConfig {
    /**
     * Cloudflare API token for authentication
     */
    private String apiToken = getSystemToken();

    /**
     * Base URL for Cloudflare API (default: https://api.cloudflare.com/client/v4)
     */
    private String baseUrl = "https://api.cloudflare.com/client/v4";

    /**
     * Connection timeout in seconds (default: 30)
     */
    private int connectionTimeoutSeconds = 30;

    /**
     * Read timeout in seconds (default: 30)
     */
    private int readTimeoutSeconds = 30;

    /**
     * Write timeout in seconds (default: 30)
     */
    private int writeTimeoutSeconds = 30;

    /**
     * Maximum number of retries for failed requests (default: 3)
     */
    private int maxRetries = 3;

    /**
     * Initial retry delay in milliseconds (default: 1000)
     */
    private long retryDelayMillis = 1000;

    /**
     * Maximum retry delay in milliseconds (default: 10000)
     */
    private long maxRetryDelayMillis = 10000;


    private static String getSystemToken() {
        if (System.getenv("CLOUDFLARE_API_TOKEN") != null) {
            return System.getenv("CLOUDFLARE_API_TOKEN");
        }
        if (System.getProperty("CLOUDFLARE_API_TOKEN") != null) {
            return System.getProperty("CLOUDFLARE_API_TOKEN");
        }
        return null;
    }
}