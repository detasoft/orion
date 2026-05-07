package pro.deta.orion.git.storage;

import lombok.RequiredArgsConstructor;
import org.slf4j.helpers.MessageFormatter;
import pro.deta.orion.config.schema.TransportConfig;

import java.net.URI;

@RequiredArgsConstructor
public enum GitAccessScheme {
    SSH("ssh"), HTTP("http"), HTTPS("https"), GIT("git");

    private final String scheme;

    public static GitAccessScheme of(String scheme) {
        for (GitAccessScheme s : values()) {
            if (s.scheme.equalsIgnoreCase(scheme)) {
                return s;
            }
        }
        throw new IllegalArgumentException(MessageFormatter.format("Unknown GitAccessScheme '{}'", scheme).getMessage());
    }


    public URI format(String user, TransportConfig transportConfig, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (user != null)
            sb.append(user).append("@");
        sb.append(transportConfig.getAddress()).append(":").append(transportConfig.getPort()).append("/").append(path);
        return URI.create(sb.toString());
    }
}
