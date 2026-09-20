package pro.deta.orion.git.parser.wire;

import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.wire.exchange.InitialRequestData;

import java.io.IOException;

@FunctionalInterface
public interface GitNativeRepositoryService {
    GitRepositoryContext open(InitialRequestData request) throws IOException;
}
