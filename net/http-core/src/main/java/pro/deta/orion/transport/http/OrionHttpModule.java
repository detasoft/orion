package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import jakarta.inject.Singleton;

@Module
public class OrionHttpModule {
    @Provides
    @Singleton
    static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Provides
    @IntoSet
    static OrionHttpRoute acmeHttpChallengeRoute(AcmeHttpChallengeRoute route) {
        return route;
    }

    @Provides
    @IntoSet
    static OrionHttpRoute gitRoute(OrionGitRoute route) {
        return route;
    }

    @Provides
    @IntoSet
    static OrionHttpRoute tokenRoute(OrionAdminIssueTokenRoute route) {
        return route;
    }

    @Provides
    @IntoSet
    static OrionHttpRoute accessControlRoute(OrionAdminAccessControlRoute route) {
        return route;
    }

    @Provides
    @IntoSet
    static OrionHttpRoute createOrUpdateUserRoute(OrionAdminCreateOrUpdateUserRoute route) {
        return route;
    }

    @Provides
    @IntoSet
    static OrionHttpRoute createRepositoryRoute(OrionAdminCreateRepositoryRoute route) {
        return route;
    }

    @Provides
    @IntoSet
    static OrionHttpRoute routesRoute(OrionAdminRoutesRoute route) {
        return route;
    }

}
