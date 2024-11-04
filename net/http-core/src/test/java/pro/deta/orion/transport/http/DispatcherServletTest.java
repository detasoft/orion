package pro.deta.orion.transport.http;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;

import static org.mockito.Mockito.*;

class DispatcherServletTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private OrionServlet exactMatchServlet;

    @Mock
    private OrionServlet wildcardMatchServlet;

    private DispatcherServlet dispatcherServlet;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dispatcherServlet = new DispatcherServlet();
    }

    @Test
    void testExactMatch() throws ServletException, IOException {
        // Setup
        when(request.getPathInfo()).thenReturn("/api/users");
        dispatcherServlet.addServlet("/api/users", exactMatchServlet);

        // Execute
        dispatcherServlet.service(request, response);

        // Verify
        verify(exactMatchServlet).service(request, response);
    }

    @Test
    void testWildcardMatch() throws ServletException, IOException {
        // Setup
        when(request.getPathInfo()).thenReturn("/api/users/123");
        dispatcherServlet.addServlet("/api/users/*", wildcardMatchServlet);

        // Execute
        dispatcherServlet.service(request, response);

        // Verify
        verify(wildcardMatchServlet).service(request, response);
    }

    @Test
    void testExactMatchPrecedence() throws ServletException, IOException {
        // Setup
        when(request.getPathInfo()).thenReturn("/api/users");
        dispatcherServlet.addServlet("/api/*", wildcardMatchServlet);
        dispatcherServlet.addServlet("/api/users", exactMatchServlet);

        // Execute
        dispatcherServlet.service(request, response);

        // Verify exact match is preferred
        verify(exactMatchServlet).service(request, response);
        verify(wildcardMatchServlet, never()).service(request, response);
    }

    @Test
    void testMultipleWildcards() throws ServletException, IOException {
        // Setup
        when(request.getPathInfo()).thenReturn("/api/users/123/details");
        dispatcherServlet.addServlet("/api/*/123/*", wildcardMatchServlet);

        // Execute
        dispatcherServlet.service(request, response);

        // Verify
        verify(wildcardMatchServlet).service(request, response);
    }
}