package sbt.internal;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;

abstract class ServerSocketWrapper {
  private ServerSocketWrapper() {}

  abstract ClientSocketWrapper getClientSocketWrapper() throws IOException;

  abstract void close() throws IOException;

  static ServerSocketWrapper fromServerSocket(final ServerSocket socket) {
    return new ServerSocketImpl(socket);
  }

  static ServerSocketWrapper fromServerSocketChannel(final ServerSocketChannel channel) {
    return new ServerSocketChannelImpl(channel);
  }

  private static final class ServerSocketImpl extends ServerSocketWrapper {
    private final ServerSocket socket;

    ServerSocketImpl(ServerSocket socket) {
      this.socket = socket;
    }

    @Override
    ClientSocketWrapper getClientSocketWrapper() throws IOException {
      return ClientSocketWrapper.fromSocket(socket.accept());
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  private static final class ServerSocketChannelImpl extends ServerSocketWrapper {
    private final ServerSocketChannel channel;

    ServerSocketChannelImpl(ServerSocketChannel channel) {
      this.channel = channel;
    }

    @Override
    ClientSocketWrapper getClientSocketWrapper() throws IOException {
      return ClientSocketWrapper.fromSocketChannel(channel.accept());
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }
}
