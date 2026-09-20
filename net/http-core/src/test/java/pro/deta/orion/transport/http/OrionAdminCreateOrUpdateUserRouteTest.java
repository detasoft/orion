package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminCreateOrUpdateUserRouteTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsReadWritePermissionFromAdminJson() throws Exception {
        String json = """
                {"id":"alice","repositories":[
                  {"repository":"project","readWrite":true,"branch":"dev"},
                  {"repository":"project","read":true,"branch":"main"}
                ]}
                """;
        OrionAdminCreateOrUpdateUserRoute.AdminUserRequest request = mapper.readValue(
                json, OrionAdminCreateOrUpdateUserRoute.AdminUserRequest.class);

        assertThat(request.repositories().getFirst().readWrite()).isTrue();
        assertThat(request.repositories().get(1).readWrite()).isFalse();
        assertThat(request.repositories().get(1).read()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsBytes(request))
                .path("repositories").get(0).path("readWrite").asBoolean()).isTrue();
    }
}
