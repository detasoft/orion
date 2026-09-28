package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.schema.config.GitTransportConfig;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.config.SshTransportConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionTransportModuleTest {
    @Test
    void transportModuleProvidesGitTransportConfigFromRuntimeConfiguration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getTransport().getGit().setEnabled(true);
        configuration.getTransport().getGit().setPort(19418);

        GitTransportConfig gitTransportConfig = OrionTransportModule.gitTransportConfig(configuration);

        assertTrue(gitTransportConfig.isEnabled());
        assertEquals(19418, gitTransportConfig.getPort());
    }

    @Test
    void transportModuleProvidesSshTransportConfigFromRuntimeConfiguration() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getTransport().getSsh().setEnabled(true);
        configuration.getTransport().getSsh().setPort(2222);

        SshTransportConfig sshTransportConfig = OrionTransportModule.sshTransportConfig(configuration);

        assertTrue(sshTransportConfig.isEnabled());
        assertEquals(2222, sshTransportConfig.getPort());
    }

    @Test
    void transportModuleReturnsDisabledSshConfigWhenAbsent() {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.setTransport(null);

        SshTransportConfig sshTransportConfig = OrionTransportModule.sshTransportConfig(configuration);

        assertFalse(sshTransportConfig.isEnabled());
    }
}
