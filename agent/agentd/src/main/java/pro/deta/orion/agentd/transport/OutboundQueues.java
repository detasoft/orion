package pro.deta.orion.agentd.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import pro.deta.orion.agent.protocol.SessionId;

/** Bounded priority queues; a busy session cannot occupy control capacity. */
final class OutboundQueues<T> {
    record Entry<T>(SessionId sessionId, T value) { }

    private final int controlCapacity;
    private final int sessionCapacity;
    private final int totalSessionCapacity;
    private final ArrayDeque<Entry<T>> control = new ArrayDeque<>();
    private final Map<SessionId, ArrayDeque<Entry<T>>> sessions = new LinkedHashMap<>();
    private int sessionCount;

    OutboundQueues(int controlCapacity, int sessionCapacity) {
        this(controlCapacity, sessionCapacity, Math.multiplyExact(sessionCapacity, 16));
    }

    OutboundQueues(int controlCapacity, int sessionCapacity, int totalSessionCapacity) {
        if (controlCapacity < 1 || sessionCapacity < 1 || totalSessionCapacity < sessionCapacity) {
            throw new IllegalArgumentException("queue capacities must be positive");
        }
        this.controlCapacity = controlCapacity;
        this.sessionCapacity = sessionCapacity;
        this.totalSessionCapacity = totalSessionCapacity;
    }

    synchronized boolean offerControl(T value) {
        if (control.size() == controlCapacity) {
            return false;
        }
        control.addLast(new Entry<>(null, value));
        return true;
    }

    synchronized boolean offerSession(SessionId sessionId, T value) {
        if (sessionCount == totalSessionCapacity) {
            return false;
        }
        ArrayDeque<Entry<T>> queue = sessions.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        if (queue.size() == sessionCapacity) {
            return false;
        }
        queue.addLast(new Entry<>(sessionId, value));
        sessionCount++;
        return true;
    }

    synchronized Entry<T> poll() {
        Entry<T> highPriority = pollControl();
        if (highPriority != null) {
            return highPriority;
        }
        return pollSession(ignored -> true);
    }

    synchronized Entry<T> pollControl() {
        return control.pollFirst();
    }

    synchronized Entry<T> pollSession(Predicate<SessionId> eligible) {
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SessionId, ArrayDeque<Entry<T>>> candidate = iterator.next();
            if (!eligible.test(candidate.getKey())) {
                continue;
            }
            ArrayDeque<Entry<T>> queue = candidate.getValue();
            Entry<T> entry = queue.removeFirst();
            sessionCount--;
            iterator.remove();
            if (!queue.isEmpty()) {
                sessions.put(entry.sessionId(), queue);
            }
            return entry;
        }
        return null;
    }

    synchronized List<Entry<T>> drain() {
        List<Entry<T>> entries = new ArrayList<>();
        Entry<T> entry;
        while ((entry = poll()) != null) {
            entries.add(entry);
        }
        return entries;
    }

    synchronized List<Entry<T>> drainSession(SessionId sessionId) {
        ArrayDeque<Entry<T>> queue = sessions.remove(sessionId);
        if (queue == null) {
            return List.of();
        }
        sessionCount -= queue.size();
        return new ArrayList<>(queue);
    }
}
