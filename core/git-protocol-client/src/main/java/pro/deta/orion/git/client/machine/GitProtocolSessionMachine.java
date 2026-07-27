package pro.deta.orion.git.client.machine;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.client.GitProtocolSession;
import pro.deta.orion.git.client.GitProtocolTransport;
import pro.deta.orion.git.client.GitProtocolTransportException;
import pro.deta.orion.git.client.GitProtocolTransportOptions;

import java.net.URI;
import java.util.Objects;

public final class GitProtocolSessionMachine {
    private final GitProtocolTransport transport;

    public GitProtocolSessionMachine(GitProtocolTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public <R> R run(
            URI remoteUri,
            GitProtocolTransportOptions options,
            GitClientMachine<R> clientMachine)
            throws GitProtocolTransportException, GitProtocolClientException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(clientMachine, "clientMachine");

        GitProtocolSession session;
        try {
            session = transport.open(clientMachine.service(), remoteUri, options);
        } catch (GitProtocolTransportException e) {
            clientMachine.close();
            throw e;
        }

        try {
            R result = runExchange(session, clientMachine);
            // Exchange succeeded: a close failure is the primary exception.
            session.close();
            return result;
        } catch (GitProtocolTransportException | GitProtocolClientException e) {
            suppressClose(session, e);
            rethrow(e);
            throw new AssertionError("unreachable");
        } finally {
            clientMachine.close();
        }
    }

    private static <R> R runExchange(GitProtocolSession session, GitClientMachine<R> machine)
            throws GitProtocolTransportException, GitProtocolClientException {
        while (true) {
            switch (machine.action()) {
                case GitClientAction.Write<R> write -> {
                    session.write(write.chunk());
                    write.chunk().release();
                    machine.written();
                }
                case GitClientAction.Read<R> ignored -> {
                    ByteBuf buf = session.read();
                    if (buf == null) {
                        machine.endOfInput();
                    } else {
                        boolean release = machine.accept(buf);
                        if (release) {
                            buf.release();
                        }
                    }
                }
                case GitClientAction.Complete<R> complete -> {
                    return complete.result();
                }
                case GitClientAction.Fail<R> fail -> {
                    throw fail.failure();
                }
            }
        }
    }

    private static void suppressClose(GitProtocolSession session, Exception primary) {
        try {
            session.close();
        } catch (GitProtocolTransportException closeEx) {
            primary.addSuppressed(closeEx);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Exception> void rethrow(Exception e) throws T {
        throw (T) e;
    }
}
