package pro.deta.orion.git.client.machine;

import io.netty.buffer.ByteBuf;

public sealed interface GitClientAction<R>
        permits GitClientAction.Write, GitClientAction.Read, GitClientAction.Complete, GitClientAction.Fail {

    record Write<R>(ByteBuf chunk) implements GitClientAction<R> {}

    record Read<R>() implements GitClientAction<R> {}

    record Complete<R>(R result) implements GitClientAction<R> {}

    record Fail<R>(GitProtocolClientException failure) implements GitClientAction<R> {}
}
