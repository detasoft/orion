package pro.deta.orion.util;

import pro.deta.orion.config.schema.HttpTransportConfig;
import pro.deta.orion.config.schema.TransportConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Utility class for network-related operations.
 */
public class NetworkUtils {

    private NetworkUtils() {
        // Utility class should not be instantiated
    }

    /**
     * Finds an available port on the local network interface.
     * 
     * @return an available port number
     * @throws IOException if there is an error finding an available port
     */
    public static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    /**
     * Checks if a specific port is available on the local network interface.
     * 
     * @param port the port number to check
     * @return true if the port is available, false otherwise
     */
    public static boolean isPortAvailable(int port) {
        if (port < 0 || port > 65535) {
            return false;
        }

        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static InetSocketAddress lookupInetSocketAddress(TransportConfig transportConfig) {
        if (transportConfig == null) {
            throw new IllegalArgumentException("TransportConfig cannot be null");
        }
        return new InetSocketAddress(transportConfig.getAddress(), transportConfig.getPort());
    }
}
