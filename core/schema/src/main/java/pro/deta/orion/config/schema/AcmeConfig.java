package pro.deta.orion.config.schema;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AcmeConfig {
    private boolean enabled = false;
    private String directoryUrl = "acme://letsencrypt.org/staging";
    private String accountEmail = null;
    private List<String> domains = new ArrayList<>();
    private String organization = null;
    private String accountKeyPath = "acme/account.keypair";
    private String domainKeyPath = "acme/domain.keypair";
    private String certificatePath = "acme/nginx.pem";
    private long authorizationTimeoutSeconds = 60;
    private long orderTimeoutSeconds = 60;
    private boolean agreeToTermsOfService = false;
    private boolean allowRequestedDomains = false;
}
