package pro.deta.orion.git;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pro.deta.orion.OrionSettingsService;
import pro.deta.orion.config.AppConfigContext;
import pro.deta.orion.settings.SettingsHolder;
import pro.deta.orion.util.OrionPathResolver;

import java.io.IOException;
import java.security.KeyPairGenerator;

public class OrionSettingsServiceTest {
    private final OrionPathResolver orionPathResolver = new OrionPathResolver(new AppConfigContext.AppConfiguration());


    private final OrionSettingsService orionConfigurationService = new OrionSettingsService(new GitRepositoryProviderImpl(orionPathResolver), orionPathResolver, new SettingsHolder());

    @Test
    public void openAndCommit() throws IOException, GitAPIException {
        orionConfigurationService.init();
        System.out.println(1);
    }

    @Test
    public void testSshConnecting() throws IOException, GitAPIException {
        orionPathResolver.getConfiguration().getSettings().setRepositoryUrl("ssh://git@github.com:bade7n/orion.git");
        orionPathResolver.getConfiguration().getSettings().setSshKey("id_rsa");
        orionConfigurationService.init();
        System.out.println(1);
    }


}
