package pro.deta.orion.git.ssh;

import lombok.Data;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Data
public abstract class CloseOnDestroyCommand implements Command {
    protected InputStream inputStream;
    protected OutputStream outputStream;
    protected OutputStream errorStream;
    protected ExitCallback exitCallback;


    @Override
    public void destroy(ChannelSession channel) {
        close(inputStream);
        close(outputStream);
        close(errorStream);
    }

    private static void close(Closeable s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
