package pro.deta.orion.acl;

import org.assertj.core.api.Assertions;
import org.bouncycastle.openssl.PEMParser;
import org.eclipse.jgit.util.Base64;
import org.junit.jupiter.api.Test;
import pro.deta.orion.util.KeyUtils;
import pro.deta.orion.util.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Arrays;

public class KeyTest {
    @Test
    public void generateRSAKey_and_load_back() throws IOException {
        Result<KeyPair> kp = KeyUtils.generateRSAKeyPair();
        Path tmp = Files.createTempFile("","");
        switch (kp) {
            case Result.Success(var keyPair) -> {
                KeyUtils.savePrivateKey(keyPair.getPrivate(), tmp);
                Result<KeyPair> resKp = KeyUtils.readRSAKeyPair(tmp);
                switch (resKp) {
                    case Result.Success<KeyPair>(var expectKeyPair) -> {
                        System.out.println(1);
                        Assertions.assertThat(expectKeyPair.getPrivate()).isEqualTo(keyPair.getPrivate());
                    }
                    case Result.Failure ex -> throw new RuntimeException("fail");
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + kp);
        }
    }

    @Test
    public void testParsingPublicKey() {
        String[] keys = new String[] {
                """
-----BEGIN PUBLIC KEY-----
MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA6QRLkfqUN0GRqLv1lZm+
HcIyGl5b2u1ahUkzOxsVFYJQVzI7lPKYKTJ6VjrtTn2Px0zrYsOryYHgicmRXGpk
8zgCii8cKv/XpLb86z68UeK317WTPQS7vJtWCXh5uYX3EKjbGjwcJroxIYNPia1Z
m5EE61Lr7IyyzylzjUlBELba/4MXJb43RAZBrlS74+z+lI9hxNCgRBmywhfPsqbx
4lJH8IoycxcuGcd1oH5HTgLqLfsXdqEU17Q2+FtvldQvjazW3kk1jE3ogdbTmqKM
/5sKS4Nvzi4wLJc7RE3SgFvyGtGOCDIYnffluz3i8+i1g7KGzPk8iPBCrpQME66J
HHPmnCBXAv1Noll76D4qdCFUTG1Z5sl5M6qtUppUMMd2eKvQoG54ulwVTQeFT6iD
55kVWKpaFreztVOw5yrhBC0+mlRdG6heY8F4FrSUwU3lJnkzNyQo882ITZXHFQGd
fCAazUawfs5J+m0itPYcodbnYa68+lUCznhqK4xf1Po5AgMBAAE=
-----END PUBLIC KEY-----
""",
                "AAAAB3NzaC1yc2EAAAADAQABAAABgQDpBEuR+pQ3QZGou/WVmb4dwjIaXlva7VqFSTM7GxUVglBXMjuU8pgpMnpWOu1OfY/HTOtiw6vJgeCJyZFcamTzOAKKLxwq/9ektvzrPrxR4rfXtZM9BLu8m1YJeHm5hfcQqNsaPBwmujEhg0+JrVmbkQTrUuvsjLLPKXONSUEQttr/gxclvjdEBkGuVLvj7P6Uj2HE0KBEGbLCF8+ypvHiUkfwijJzFy4Zx3WgfkdOAuot+xd2oRTXtDb4W2+V1C+NrNbeSTWMTeiB1tOaooz/mwpLg2/OLjAslztETdKAW/Ia0Y4IMhid9+W7PeLz6LWDsobM+TyI8EKulAwTrokcc+acIFcC/U2iWXvoPip0IVRMbVnmyXkzqq1SmlQwx3Z4q9Cgbni6XBVNB4VPqIPnmRVYqloWt7O1U7DnKuEELT6aVF0bqF5jwXgWtJTBTeUmeTM3JCjzzYhNlccVAZ18IBrNRrB+zkn6bSK09hyh1udhrrz6VQLOeGorjF/U+jk=",
                "AAAAC3NzaC1lZDI1NTE5AAAAING9vjkHvFLOFa+ilpIQOzZT7hQLpEwSY7Q81g4iY3XX",
                "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKQwDKljzFyujQvGV0rzQWgcMN3D7N7uctwpNB6BcSTDNGNN/pakJsjLO0xeGoO+ZPySiB8fiQW/aj7IxUoAXao=",
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBF33wMcGqkvond1GtRq36cNwcBzJaTYujFjbckvG8fOCY8agi7QAU998Ely8zb0ZH3ttgzeEJN0pcWl2DCEFMbU=",
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAea3dh/09YLAW6avBUEmRywMDHCoHMsVIkmk73QuwtO",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCgeia1LIIfqT7VcLBpc5M0qoSx3dKwW9Yp3yGNOXAuPxUoD9gNYuMKnqaUv1gWwPJoUDX4VcxeKbW1rGjRDkMps+h2J/UGUJSER/In6PMPBmfVr3tEpU0YeFNDqN7pi7u7hFliVSACG/SIDVNY6o3ih/dID67Mf6M3v8bvUyWwVJYlWuuZj43f2QnVmwrD/+GSSjSwKE+vxkB2TUopFQPc4HLo2UT3aqHz6xhS8ISGtgslx6HPl/dPocWD0ei4PKnnGlAWXND8/fI752xJJub+goqKuXF6yRvXXYhGONU1OfMHanPFFX1gNl2V9oDbw3mISv9l3/e0KkseCuhYOUceCsIYfEAmYafG1neWzCjO0nk1nxoXPjE3QWNHtfbFZBQQQyj+zDTlE+plNWC1dyQ0BoOGa/qa4ndoW/+WhIamdhkY/8gyxvmFeBTdkYcZUuk/ZuMrMYKGCtchmaLUj6NVLIv8Xug7FtyS3q7J432JLR+1Hmf1pAKvOv89YApJBbM="
        };
        int i = 0;
        for (String key : keys) {
            System.out.println("Key position: " + i++ );
            KeyUtils.readPublicKeyFromString(key);
        }
    }
}
