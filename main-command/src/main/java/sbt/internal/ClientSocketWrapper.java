package sbt.internal;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

abstract class ClientSocketWrapper {
  private ClientSocketWrapper() {}

  abstract void write(int value) throws IOException;

  abstract void write(byte[] value) throws IOException;

  abstract void write(byte[] b, int offset, int len) throws IOException;

  abstract void close() throws IOException;

  abstract int read() throws IOException;

  abstract void flush() throws IOException;

  static ClientSocketWrapper fromSocket(Socket socket) {
    return new SocketImpl(socket);
  }

  static ClientSocketWrapper fromSocketChannel(SocketChannel channel) {
    return new SocketChannelImpl(channel);
  }

  private static final class SocketChannelImpl extends ClientSocketWrapper {
    private final SocketChannel channel;

    private SocketChannelImpl(SocketChannel channel) {
      this.channel = channel;
    }

    @Override
    void write(int value) throws IOException {
      ByteBuffer buf = ByteBuffer.allocate(4);
      buf.putInt(value);
      channel.write(buf);
    }

    @Override
    void write(byte[] value) throws IOException {
      channel.write(ByteBuffer.wrap(value));
    }

    @Override
    void write(byte[] b, int offset, int len) throws IOException {
      channel.write(ByteBuffer.wrap(b, offset, len));
    }

    @Override
    void close() throws IOException {
      channel.close();
    }

    @Override
    int read() throws IOException {
      ByteBuffer buf = ByteBuffer.allocate(4);
      if (-1 == channel.read(buf)) {
        return -1;
      } else {
        return buf.getInt();
      }
    }

    @Override
    void flush() {}
  }

  private static final class SocketImpl extends ClientSocketWrapper {
    private final Socket socket;

    private SocketImpl(Socket socket) {
      this.socket = socket;
    }

    @Override
    void write(int value) throws IOException {
      socket.getOutputStream().write(value);
    }

    @Override
    void write(byte[] value) throws IOException {
      socket.getOutputStream().write(value);
    }

    @Override
    void write(byte[] b, int offset, int len) throws IOException {
      socket.getOutputStream().write(b, offset, len);
    }

    @Override
    void close() throws IOException {
      socket.getOutputStream().close();
      socket.getInputStream().close();
    }

    @Override
    int read() throws IOException {
      return socket.getInputStream().read();
    }

    @Override
    void flush() throws IOException {
      socket.getOutputStream().flush();
    }
  }
}
