package pro.deta.orion.git.parser.wire.continuation.exchange;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InitialRequestData {
    private final InitialRequestService service;
    private final String repositoryPath;
    private final String host;
    private final Map<String, String> parameters;

    public InitialRequestData(
            InitialRequestService service,
            String repositoryPath,
            String host,
            Map<String, String> parameters) {
        this.service = service;
        this.repositoryPath = repositoryPath;
        this.host = host;
        this.parameters = Collections.unmodifiableMap(
                new LinkedHashMap<>(parameters));
    }

    public InitialRequestService getService() {
        return service;
    }

    public String getRepositoryPath() {
        return repositoryPath;
    }

    public String getHost() {
        return host;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }
}
