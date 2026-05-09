package pro.deta.orion.transport.http;

import org.junit.jupiter.api.Test;
import pro.deta.orion.config.schema.HttpTransportConfig;
import pro.deta.orion.config.schema.HttpsTransportConfig;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.util.NetworkUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

class JettyHTTPServerTest {

    @Test
    void testServerWithHttpAndHttps() throws Exception {
        // Configure test ports
        int httpPort = NetworkUtils.findAvailablePort();
        int httpsPort = NetworkUtils.findAvailablePort();

        // Create configuration
        OrionConfiguration orionConfiguration = new OrionConfiguration();
        OrionConfiguration.AppTransport transports = new OrionConfiguration.AppTransport();
        transports.setHttp(new HttpTransportConfig("localhost", httpPort));
        transports.setHttps(new HttpsTransportConfig("localhost", httpsPort));
        orionConfiguration.setTransport(transports);

        // Create and start server
        DispatcherServlet dispatcherServlet = new DispatcherServlet();
        PlainOkServlet okServlet = new PlainOkServlet();
        dispatcherServlet.register(okServlet);
        JettyHTTPServer server = new JettyHTTPServer(orionConfiguration, dispatcherServlet);
        server.onStart();

        try {
            // Configure SSL context to trust our self-signed certificate
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {TrustAllX509TrustManager.INSTANCE}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

            // Test HTTP connection
            URL httpUrl = server.relativiseHttp(dispatcherServlet.relativise(okServlet.relativise()));
            HttpURLConnection httpConn = (HttpURLConnection) httpUrl.openConnection();
            assertThat(httpConn.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);

            // Test HTTPS connection
            URL httpsUrl = server.relativiseHttps(dispatcherServlet.relativise(okServlet.relativise()));
            HttpsURLConnection httpsConn = (HttpsURLConnection) httpsUrl.openConnection();
            assertThat(httpsConn.getResponseCode()).isEqualTo(HttpURLConnection.HTTP_OK);

        } finally {
            server.onStop();
        }
    }
}
