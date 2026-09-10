package pro.deta.maven.rust;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class CargoTestOnCleanMojoTest {
    @Test
    void runsWhenCleanWasRequestedAndCachedTestWasSkipped() {
        assertThat(CargoTestOnCleanMojo.shouldRun(List.of("clean", "test"), false)).isTrue();
        assertThat(CargoTestOnCleanMojo.shouldRun(
                List.of("org.apache.maven.plugins:maven-clean-plugin:3.4.1:clean", "test"),
                false)).isTrue();
    }

    @Test
    void skipsWithoutCleanOrAfterNormalTestExecution() {
        assertThat(CargoTestOnCleanMojo.shouldRun(List.of("test"), false)).isFalse();
        assertThat(CargoTestOnCleanMojo.shouldRun(List.of("clean", "test"), true)).isFalse();
    }
}
