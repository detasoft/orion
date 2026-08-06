package pro.deta.orion.test.duration;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

@Name(TestDurationJfrEvent.NAME)
@Label("Orion Test Duration")
@Category({"Orion", "Tests"})
final class TestDurationJfrEvent extends Event {
    static final String NAME = "pro.deta.orion.test.duration";

    @Label("Run ID")
    String runId;

    @Label("Module")
    String module;

    @Label("Test ID")
    String testId;

    @Label("Class Name")
    String className;

    @Label("Method Name")
    String methodName;

    @Label("Display Name")
    String displayName;

    @Label("Status")
    String status;

    @Label("Duration Millis")
    long durationMillis;

    @Label("Reason")
    String reason;
}
