package pro.deta.orion.git.parser.wire;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record GitInitialServiceRequest(
        Service service,
        String repositoryPath,
        Map<String, String> parameters) {

    public GitInitialServiceRequest {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(repositoryPath, "repositoryPath");
        Objects.requireNonNull(parameters, "parameters");
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    public enum Service {
        UPLOAD_PACK("git-upload-pack"),
        RECEIVE_PACK("git-receive-pack");

        private final String wireName;

        Service(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static Service parse(String wireName) {
            for (Service service : values()) {
                if (service.wireName.equals(wireName)) {
                    return service;
                }
            }
            throw new IllegalArgumentException("Unsupported Git service: " + wireName);
        }
    }
}
