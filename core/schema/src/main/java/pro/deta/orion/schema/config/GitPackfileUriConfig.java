package pro.deta.orion.schema.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Data
public class GitPackfileUriConfig {
    public static final String AUTO_BASE_URI = "auto";

    private String baseUri = null;
    private List<String> trustedProxyAddresses = new ArrayList<>();

    public boolean isConfigured() {
        return baseUri != null && !baseUri.isBlank();
    }

    public boolean isAuto() {
        return isConfigured()
                && AUTO_BASE_URI.equals(
                        baseUri.trim().toLowerCase(Locale.ROOT));
    }
}
