package pro.deta.orion.git;

import lombok.Getter;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import pro.deta.orion.test.util.ResourceUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

public class BaseOrionTest {
    @Getter
    private TestInfo testInfo;

    @BeforeAll
    public static void configureLogger() {
        ResourceUtils.configureDefaultLogging();
        FS.FileStoreAttributes.setBackground(true);
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
}
