package pro.deta.orion.test.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.OrionEvent;

class TestEventsTest {
    @Test
    void drainsEventsBeforeClosingTheirConsumersAndStopsTheDispatcher() {
        List<String> operations = new ArrayList<>();
        TestEvents events = new TestEvents();
        OrionEventManager manager = events.manager();
        manager.subscribe(Message.class, ignored -> operations.add("event"));
        events.own(() -> operations.add("close"));
        manager.publish(new Message());

        events.close();

        assertThat(operations).containsExactly("event", "close");
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void closesEveryResourceInReverseOrderEvenWhenOneFails() {
        List<String> operations = new ArrayList<>();
        TestEvents events = new TestEvents();
        OrionEventManager manager = events.manager();
        events.own(() -> operations.add("first"));
        events.own(() -> {
            operations.add("second");
            throw new IOException("resource failure");
        });
        events.own(() -> operations.add("third"));

        assertThatThrownBy(events::close).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);

        assertThat(operations).containsExactly("third", "second", "first");
        assertThat(manager.isRunning()).isFalse();
        events.close();
    }

    private static final class Message extends OrionEvent {}
}
