package pro.deta.orion;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.config.AppConfigContext;
import pro.deta.orion.git.GitRepositoryProvider;
import pro.deta.orion.settings.Settings;
import pro.deta.orion.settings.SettingsHolder;
import pro.deta.orion.util.OrionPathResolver;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

@Slf4j
@RequiredArgsConstructor
public class OrionSettingsService {
    private final GitRepositoryProvider gitRepositoryProvider;
    private final AppConfigContext.SettingsConfig settingsConfig;
    private final OrionPathResolver orionPathResolver;
    private final SettingsHolder settingsHolder;
    private final Path workingPath;
    private final String settingsPath;

    public OrionSettingsService(GitRepositoryProvider gitRepositoryProvider, OrionPathResolver orionPathResolver, SettingsHolder settingsHolder) {
        this.gitRepositoryProvider = gitRepositoryProvider;
        this.settingsConfig = orionPathResolver.getConfiguration().getSettings();
        this.settingsHolder = settingsHolder;
        this.orionPathResolver = orionPathResolver;
        workingPath = orionPathResolver.resolve(settingsConfig.getWorkingDir()).resolve("orion-settings-work");
        settingsPath = settingsConfig.getSettingsFile();
        SystemReader.setInstance(new OrionJGitSystemReader(SystemReader.getInstance(), orionPathResolver));
    }

    public void init() {
        try {
            FileUtils.deleteDirectory(workingPath.toFile());
            try (Git git = checkoutSettings(settingsConfig)) {
                Path orionConfig = workingPath.resolve(settingsPath);
                if (!orionConfig.toFile().exists()) {

                    Settings s = settingsHolder.generateDefaultSettings(orionConfig, RandomStringUtils.randomAlphanumeric(10));
                    saveConfig(s, orionConfig);
                    addAndCommit(git, "default scheme applied", orionConfig.relativize(workingPath));
                }
            }

        } catch (Exception e) {
            log.error("Error while preparing configuration repository.", e);
            throw new IllegalStateException("Configuration repository not initialized.", e);
        }
    }

    private void saveConfig(Settings s, Path orionConfig) throws IOException {
        XmlMapper xmlMapper = new XmlMapper();
        xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
        xmlMapper.writeValue(orionConfig.toFile(), s);
    }

    private Git checkoutSettings(AppConfigContext.SettingsConfig settingsConfig) throws GitAPIException, IOException {
        URI repositoryToClone = URI.create(settingsConfig.getRepositoryUrl());
// сделать и проверить работу ssh local http/https авторизации
        // если репозиторий создан - прочитать файл, если файла нет - создать

        CloneCommand cloneCommand = Git.cloneRepository()
                .setDirectory(workingPath.toFile())
                .setDepth(1)
                .setBranch(settingsConfig.getBranchName())
                ;
        String scheme = repositoryToClone.getScheme();
        if (scheme.equalsIgnoreCase("local")) {
            Repository r = gitRepositoryProvider.findOrCreate(repositoryToClone.getHost());
            repositoryToClone = r.getDirectory().toURI();
        } else if (scheme.equalsIgnoreCase("ssh")) {
//            SshSessionFactory sshSessionFactory = new JschConfigSessionFactory() {
//                @Override
//                protected void configure(OpenSshConfig.Host host, Session session ) {
//                    // do nothing
//                }
//            };
            cloneCommand.setTransportConfigCallback( new TransportConfigCallback() {
                @Override
                public void configure( Transport transport ) {
                    SshTransport sshTransport = (SshTransport)transport;
//                    sshTransport.setSshSessionFactory( sshSessionFactory );
                }
            } );
        } else if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")){
            cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(settingsConfig.getUsername(), settingsConfig.getPassword()));
        }
        return cloneCommand
                .setURI(repositoryToClone.toString())
                .call();
    }

    private void addAndCommit(Git git, String message, Path... paths) throws GitAPIException {
        AddCommand addCommand = git.add();
        for(Path p: paths) {
            addCommand.addFilepattern(p.toString());
        }
        addCommand.call();
        git.commit().setMessage(message).call();
        git.push().call();
    }


}
