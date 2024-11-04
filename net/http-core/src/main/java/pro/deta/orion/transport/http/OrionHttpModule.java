package pro.deta.orion.transport.http;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;

@Module
public class OrionHttpModule {
    @Provides
    @Singleton
    static DispatcherServlet dispatcherServlet(OrionGitServlet orionGitServlet) {
        DispatcherServlet dispatcherServlet = new DispatcherServlet();
        dispatcherServlet.register(orionGitServlet);
        return dispatcherServlet;
    }

}
