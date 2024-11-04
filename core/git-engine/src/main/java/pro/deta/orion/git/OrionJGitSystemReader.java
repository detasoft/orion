package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.ToString;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.UserConfigFile;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import pro.deta.orion.config.WorkDir;
import pro.deta.orion.util.ConfigurationContext;

import java.io.File;
import java.nio.file.Path;

@Getter
@ToString
public class OrionJGitSystemReader extends SystemReader.Delegate {
    private final Path jgitRoot;
    /**
     * Create a delegating system reader
     *
     * @param delegate the system reader to delegate to
     */
    @Inject
    public OrionJGitSystemReader(@Named("default") SystemReader delegate,@WorkDir Path workDir) {
        super(delegate);
        this.jgitRoot = workDir.resolve(".jgit");
    }

    public FileBasedConfig openSystemConfig(Config parent, FS fs) {
        File gitconfig = jgitRoot.resolve("gitconfig").toFile();
        if (gitconfig.exists())
            return new FileBasedConfig(parent, gitconfig, fs);
        else {
            return new FileBasedConfig(parent, null, fs) {
                @Override
                public void load() {
                    // empty, do not load
                }

                @Override
                public boolean isOutdated() {
                    // regular class would bomb here
                    return false;
                }
            };
        }
    }


    @Override
    public FileBasedConfig openUserConfig(Config parent, FS fs) {
        File homeFile = new File(fs.userHome(), ".gitconfig"); //$NON-NLS-1$
        Path xdgPath = getXdgConfigDirectory(fs);
        if (xdgPath != null) {
            Path configPath = xdgPath.resolve("git") //$NON-NLS-1$
                    .resolve(Constants.CONFIG);
            return new UserConfigFile(parent, homeFile, configPath.toFile(),
                    fs);
        }
        return new FileBasedConfig(parent, homeFile, fs);
    }

    @Override
    public FileBasedConfig openJGitConfig(Config parent, FS fs) {
        return new FileBasedConfig(parent, jgitRoot.resolve(".jgitconfig").toFile(), fs);
    }
}
