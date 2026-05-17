package pro.deta.orion.test;

import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.util.NetworkUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TestPorts {
    private static final String HOST = "localhost";
    private static final int DEFAULT_BATCH_SIZE = 4;
    private static final Set<Integer> RESERVED_PORTS = new HashSet<>();

    private TestPorts() {
    }

    static synchronized Batch nextBatch() throws IOException {
        for (int i = 0; i < 100; i++) {
            int candidate = NetworkUtils.findAvailablePort();
            int base = candidate - 1;
            if (base <= 0 || base + DEFAULT_BATCH_SIZE - 1 > 65_535) {
                continue;
            }
            if (rangeIsReserved(base, DEFAULT_BATCH_SIZE)) {
                continue;
            }
            if (rangeIsAvailable(base, DEFAULT_BATCH_SIZE)) {
                reserveRange(base, DEFAULT_BATCH_SIZE);
                return new Batch(base);
            }
        }
        throw new IOException("Failed to find an unused contiguous port batch");
    }

    private static boolean rangeIsReserved(int base, int count) {
        for (int i = 0; i < count; i++) {
            if (RESERVED_PORTS.contains(base + i)) {
                return true;
            }
        }
        return false;
    }

    private static void reserveRange(int base, int count) {
        for (int i = 0; i < count; i++) {
            RESERVED_PORTS.add(base + i);
        }
    }

    private static boolean rangeIsAvailable(int base, int count) {
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            InetAddress address = InetAddress.getByName(HOST);
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(address, base + i));
                sockets.add(socket);
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    record Batch(int base) {
        int git() {
            return base;
        }

        int http() {
            return base + 1;
        }

        int ssh() {
            return base + 2;
        }

        int https() {
            return base + 3;
        }

        void configure(OrionConfiguration configuration) {
            configuration.getTransport().getGit().setAddress(HOST);
            configuration.getTransport().getGit().setPort(git());

            configuration.getTransport().getHttp().setAddress(HOST);
            configuration.getTransport().getHttp().setPort(http());

            configuration.getTransport().getSsh().setAddress(HOST);
            configuration.getTransport().getSsh().setPort(ssh());

            configuration.getTransport().getHttps().setAddress(HOST);
            configuration.getTransport().getHttps().setPort(https());
        }
    }
}
