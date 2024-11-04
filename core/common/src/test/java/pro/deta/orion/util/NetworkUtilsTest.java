package pro.deta.orion.util;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.TransportConfig;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class NetworkUtilsTest {

    @Test
    void lookupInetSocketAddress_WithValidConfig_ReturnsCorrectAddress() {
        TransportConfig config = new TransportConfig("test-host", 8080, 10, true);
        InetSocketAddress address = NetworkUtils.lookupInetSocketAddress(config);
        
        assertEquals("test-host", address.getHostString());
        assertEquals(8080, address.getPort());
    }

    @Test
    void lookupInetSocketAddress_WithDefaultConfig_ReturnsDefaultAddress() {
        TransportConfig config = new TransportConfig();
        InetSocketAddress address = NetworkUtils.lookupInetSocketAddress(config);
        
        assertEquals("localhost", address.getHostString());
        assertEquals(9418, address.getPort());
    }

    @Test
    void lookupInetSocketAddress_WithNullConfig_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            NetworkUtils.lookupInetSocketAddress(null);
        });
    }
}