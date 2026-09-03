package pro.deta.orion.schema.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SigningKeyReferenceConfig {
    private String alias;
    private long version;
}
