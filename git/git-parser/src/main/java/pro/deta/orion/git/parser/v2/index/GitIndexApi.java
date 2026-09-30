package pro.deta.orion.git.parser.v2.index;

import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;

import java.io.IOException;

public interface GitIndexApi {
    GitIndexAccess createAccess() throws IOException;

    GitHashAlgorithm hashAlgorithm();
}
