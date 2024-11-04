package pro.deta.orion.git;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.ToString;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

@Getter
@ToString
public class PropertyOrionJGitSystemReader extends SystemReader.Delegate {
    private static final FileBasedConfig noConfig = new FileBasedConfig(null, null, null) {
        @Override
        public void load() {
            // empty, do not load
        }

        @Override
        public boolean isOutdated() {
            // regular class would bomb here
            return false;
        }

        @Override
        protected byte[] readIncludedConfig(String relPath) {
            return null;
        }

        @Override
        public void save() throws IOException {
        }

    };

    private volatile String hostname;

    /**
     * Create a delegating system reader
     *
     * @param delegate the system reader to delegate to
     */
    @Inject
    public PropertyOrionJGitSystemReader(@Named("default") SystemReader delegate) {
        super(delegate);
    }

    public FileBasedConfig openSystemConfig(Config parent, FS fs) {
        return noConfig;
    }


    @Override
    public FileBasedConfig openUserConfig(Config parent, FS fs) {
        return noConfig;
    }

    @Override
    public FileBasedConfig openJGitConfig(Config parent, FS fs) {
        return noConfig;
    }

    @Override
    public String getProperty(String key) {
        return System.getProperty(key);
    }

    @Override
    public String getenv(String variable) {
        return System.getenv(variable);
    }

    @Override
    public String getHostname() {
        if (hostname == null) {
            try {
                InetAddress localMachine = InetAddress.getLocalHost();
                hostname = localMachine.getCanonicalHostName();
            } catch (UnknownHostException e) {
                // we do nothing
                hostname = "localhost"; //$NON-NLS-1$
            }
            assert hostname != null;
        }
        return hostname;
    }

    @Override
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @Override
    public int getTimezone(long when) {
        return getTimeZone().getOffset(when) / (60 * 1000);
    }
}