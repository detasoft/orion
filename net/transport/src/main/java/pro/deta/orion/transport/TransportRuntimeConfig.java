package pro.deta.orion.transport;

import pro.deta.orion.config.schema.OrionConfiguration;

final class TransportRuntimeConfig {
    private TransportRuntimeConfig() {
    }

    static boolean isHttpTransportEnabled(OrionConfiguration configuration) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        return transport != null
                && ((transport.getHttp() != null && transport.getHttp().isEnabled())
                || (transport.getHttps() != null && transport.getHttps().isEnabled()));
    }

    static boolean isGitTransportEnabled(OrionConfiguration configuration) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        return transport != null && transport.getGit() != null && transport.getGit().isEnabled();
    }

    static boolean isSshTransportEnabled(OrionConfiguration configuration) {
        OrionConfiguration.AppTransport transport = configuration.getTransport();
        return transport != null && transport.getSsh() != null && transport.getSsh().isEnabled();
    }
}
