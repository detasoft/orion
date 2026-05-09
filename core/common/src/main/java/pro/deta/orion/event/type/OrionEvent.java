package pro.deta.orion.event.type;

import lombok.Getter;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base type for events sent through {@code OrionEventManager}.
 *
 * <p>An Orion event is a domain-level message passed inside the same JVM. It is not a network message and it is not a
 * persisted audit log entry by itself. Each subclass is the event payload: it names the event type through its Java class
 * and stores the data handlers need to react to it. The base event also records when the event object was created; this
 * is not the same as the later asynchronous processing time.</p>
 *
 * <p>Events are sealed so the supported event set is explicit and easy to audit. The event manager marks an event as
 * processed after all registered handlers for its concrete class have run. The flag is intentionally small: it is useful
 * for tests and diagnostics, but event handlers should communicate real state changes through their own domain objects
 * or services.</p>
 */
@Getter
public sealed abstract class OrionEvent permits GitReceiveOrionEvent, GitUploadOrionEvent, RequestToAclUpdate {
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean processed = new AtomicBoolean(false);

    public void setProcessed() {
        processed.set(true);
    }

    public boolean isProcessed() {
        return processed.get();
    }

    @Override
    public final String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        sb.append("{createdAt=").append(createdAt);
        sb.append(", processed=").append(isProcessed());
        appendToStringFields(sb);
        sb.append('}');
        return sb.toString();
    }

    protected void appendToStringFields(StringBuilder sb) {
    }
}
