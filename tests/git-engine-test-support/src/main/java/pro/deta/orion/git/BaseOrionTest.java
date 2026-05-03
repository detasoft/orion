package pro.deta.orion.git;

import lombok.Getter;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import pro.deta.orion.test.util.ResourceUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BaseOrionTest {
    @Getter
    private TestInfo testInfo;

    @BeforeAll
    public static void configureLogger() {
        ResourceUtils.configureDefaultLogging();
        FS.FileStoreAttributes.setBackground(true);
        SystemReader.setInstance(new TestJGitSystemReader(SystemReader.getInstance()));
    }

    @BeforeEach
    protected void init(TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    protected Path createTestRepositoryDirectory() throws IOException {
        String testClass = testInfo.getTestClass()
                .map(Class::getSimpleName)
                .orElse("GitEngineTest");
        String testMethod = testInfo.getTestMethod()
                .map(Method::getName)
                .orElse("repository");

        return Files.createTempDirectory("%s-%s-".formatted(testClass, testMethod));
    }

    private static class TestJGitSystemReader extends SystemReader.Delegate {
        private static final FileBasedConfig NO_CONFIG = new FileBasedConfig(null, null, null) {
            @Override
            public void load() {
            }

            @Override
            public boolean isOutdated() {
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

        private TestJGitSystemReader(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public FileBasedConfig openSystemConfig(Config parent, FS fs) {
            return NO_CONFIG;
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return NO_CONFIG;
        }

        @Override
        public FileBasedConfig openJGitConfig(Config parent, FS fs) {
            return NO_CONFIG;
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
                    hostname = InetAddress.getLocalHost().getCanonicalHostName();
                } catch (UnknownHostException e) {
                    hostname = "localhost";
                }
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
}
