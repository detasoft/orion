package pro.deta.orion.settings;

import org.jeasy.random.EasyRandom;
import org.jeasy.random.EasyRandomParameters;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.settings.Settings.getAccessControlClasses;

public class SettingsTest {
    EasyRandomParameters parameters = new EasyRandomParameters()
            .seed(123L)
            .objectPoolSize(100)
            .randomizationDepth(3)
            .charset(StandardCharsets.UTF_8)
            .timeRange(LocalTime.of(9, 0, 0), LocalTime.of(17, 0, 0))
            .dateRange(LocalDate.now(), LocalDate.now().plusDays(1))
            .stringLengthRange(5, 50)
            .collectionSizeRange(1, 10)
            .scanClasspathForConcreteTypes(true)
            .overrideDefaultInitialization(false)
            .ignoreRandomizationErrors(true);

    EasyRandom easyRandom = new EasyRandom(parameters);

    @Test
    @SuppressWarnings("rawtypes")
    public void testAccessControlFreeze() {
        for(Class<CloneToUnmodifiable> cls: getAccessControlClasses()) {
            CloneToUnmodifiable orig = easyRandom.nextObject(cls);
            Object freeze = orig.unmodify();
            assertThat(freeze).isEqualTo(orig);
        }
    }
}