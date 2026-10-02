package pro.deta.orion.test;

import pro.deta.orion.bootstrap.config.OrionConfiguration;

final class TestPorts {
    private static final String HOST = "localhost";

    private TestPorts() {
    }

    static void configure(OrionConfiguration configuration) {
        configuration.getTransport().getGit().setAddress(HOST);
        configuration.getTransport().getGit().setPort(0);

        configuration.getTransport().getHttp().setAddress(HOST);
        configuration.getTransport().getHttp().setPort(0);

        configuration.getTransport().getSsh().setAddress(HOST);
        configuration.getTransport().getSsh().setPort(0);
    }
}
