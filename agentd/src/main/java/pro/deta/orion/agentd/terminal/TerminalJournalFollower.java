package pro.deta.orion.agentd.terminal;

import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.AgentProtocolException;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agentd.journal.FileSystemSessionJournalReader;
import pro.deta.orion.agentd.journal.JournalAvailabilityMonitor;
import pro.deta.orion.agentd.journal.JournalCursorGap;
import pro.deta.orion.agentd.journal.JournalReadBoundary;
import pro.deta.orion.agentd.journal.JournalReadLimits;
import pro.deta.orion.agentd.journal.JournalReadPage;
import pro.deta.orion.agentd.journal.JournalReadPosition;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;

final class TerminalJournalFollower {
    private final JournalReadLimits limits;
    private final FileSystemSessionJournalReader reader = new FileSystemSessionJournalReader();
    private final SessionEventCodec codec = new SessionEventCodec(AgentProtocolLimits.journalDefaults());

    TerminalJournalFollower() {
        this(new JournalReadLimits(256, AgentProtocolLimits.HARD_MAX_JOURNAL_RECORD_BYTES));
    }

    TerminalJournalFollower(JournalReadLimits limits) {
        this.limits = limits;
    }

    Inspection inspect(Path sessionDirectory) {
        return inspect(sessionDirectory, false);
    }

    Inspection inspectUntilTail(Path sessionDirectory) {
        return inspect(sessionDirectory, true);
    }

    private Inspection inspect(Path sessionDirectory, boolean untilTail) {
        Optional<EventId> cursor = Optional.empty();
        Optional<JournalReadPosition> position = Optional.empty();
        while (true) {
            JournalReadPage page = reader.readPage(sessionDirectory, cursor, position, limits);
            try {
                for (SessionEventRecord record : page.records()) {
                    cursor = Optional.of(record.eventId());
                    if (codec.decodeKnownPayload(record).orElse(null)
                            instanceof SessionEventPayload.ProcessExited exited) {
                        return new Inspection.Exited(exited.exitCode());
                    }
                }
            } catch (AgentProtocolException exception) {
                return failed("journal failure: " + TerminalDiagnostics.detail(exception));
            }
            position = page.nextPosition();
            if (page.boundary() == JournalReadBoundary.PAGE_LIMIT) {
                if (untilTail) {
                    continue;
                }
                return new Inspection.Active();
            }
            if (page.boundary() == JournalReadBoundary.GAP) {
                JournalCursorGap gap = page.gap().orElseThrow();
                return new Inspection.Failed(gapDetail(gap));
            }
            if (page.boundary() == JournalReadBoundary.ISSUE) {
                return failed("journal failure: " + page.issue().orElseThrow().detail());
            }
            return new Inspection.Active();
        }
    }

    Result follow(Path sessionDirectory, OutputStream output, BooleanSupplier stopped) {
        Optional<EventId> cursor = Optional.empty();
        Optional<JournalReadPosition> position = Optional.empty();
        try (JournalAvailabilityMonitor monitor = new JournalAvailabilityMonitor(sessionDirectory)) {
            while (true) {
                JournalReadPage page = reader.readPage(sessionDirectory, cursor, position, limits);
                for (SessionEventRecord record : page.records()) {
                    Optional<SessionEventPayload> payload = codec.decodeKnownPayload(record);
                    cursor = Optional.of(record.eventId());
                    position = page.nextPosition();
                    if (payload.orElse(null) instanceof SessionEventPayload.PtyOutput terminalOutput) {
                        output.write(terminalOutput.bytes().toByteArray());
                        output.flush();
                    } else if (payload.orElse(null) instanceof SessionEventPayload.ProcessExited exited) {
                        return new Result.Exited(exited.exitCode());
                    }
                }
                if (page.boundary() == JournalReadBoundary.GAP) {
                    JournalCursorGap gap = page.gap().orElseThrow();
                    return new Result.Failed(gapDetail(gap));
                }
                if (page.boundary() == JournalReadBoundary.ISSUE) {
                    return failedResult("journal failure: " + page.issue().orElseThrow().detail());
                }
                if (stopped.getAsBoolean()) {
                    return new Result.Detached();
                }
                if (page.boundary() == JournalReadBoundary.PAGE_LIMIT) {
                    continue;
                }
                if (monitor.await() == JournalAvailabilityMonitor.Wakeup.CLOSED) {
                    return new Result.Detached();
                }
            }
        } catch (AgentProtocolException | IOException exception) {
            return failedResult("journal failure: " + TerminalDiagnostics.detail(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result.Detached();
        }
    }

    private static String gapDetail(JournalCursorGap gap) {
        return "required journal history gap after event "
                + Long.toUnsignedString(gap.requestedEventId().value())
                + "; first available event is "
                + Long.toUnsignedString(gap.firstAvailableEventId().value());
    }

    private static Inspection.Failed failed(String detail) {
        return new Inspection.Failed(TerminalDiagnostics.bounded(detail));
    }

    private static Result.Failed failedResult(String detail) {
        return new Result.Failed(TerminalDiagnostics.bounded(detail));
    }

    sealed interface Result {
        record Exited(int exitCode) implements Result {
        }

        record Detached() implements Result {
        }

        record Failed(String detail) implements Result {
        }
    }

    sealed interface Inspection {
        record Active() implements Inspection {
        }

        record Exited(int exitCode) implements Inspection {
        }

        record Failed(String detail) implements Inspection {
        }
    }
}
