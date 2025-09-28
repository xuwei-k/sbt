package sbt.internal

import org.scalatest.freespec.AnyFreeSpec

import java.net.StandardProtocolFamily
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Paths
import scala.util.Random

class BootServerSocketTest extends AnyFreeSpec {
  private def withServerAndClient[A](action: (SocketWrapper, SocketChannel) => A): A = {
    val tmp = Files.createTempDirectory("")
    val path = BootServerSocket.socketLocation(tmp)
    val dir = Paths.get(path).getParent
    try {
      if (!Files.isDirectory(dir)) {
        Files.createDirectories(dir)
      }
      val serverSocket = BootServerSocket.newUnixDomainSocket(path, false, true)
      try {
        val expect = if (scala.util.Properties.isJavaAtLeast(17)) {
          "ServerSocketChannelImpl"
        } else {
          "ServerSocketImpl"
        }
        assert(serverSocket.getClass.getSimpleName == expect)
        val client = java.nio.channels.SocketChannel
          .open(StandardProtocolFamily.UNIX)
        assert(client.connect(java.net.UnixDomainSocketAddress.of(path)))

        val server = serverSocket.accept()

        action(server, client)
      } finally {
        serverSocket.close()
      }
    } finally {
      Files.deleteIfExists(Paths.get(path))
      Files.deleteIfExists(dir)
    }
  }

  "BootServerSocket" - {
    if (!scala.util.Properties.isWin) {
      val values: List[Byte] =
        Random.shuffle((Byte.MinValue to Byte.MaxValue).toList.map(_.toByte))

      "server to client" in withServerAndClient { (server, client) =>
        values.foreach(x => server.write(x))
        server.close()

        val clientReadResult =
          Iterator
            .continually {
              val buf = ByteBuffer.allocate(1)
              val x = client.read(buf)
              x -> buf.array().head
            }
            .takeWhile(_._1 != -1)
            .map(_._2)
            .toList

        assert(clientReadResult == values)
      }

      "client to server" in withServerAndClient { (server, client) =>
        client.write(ByteBuffer.wrap(values.toArray))
        client.close()

        val result = Iterator
          .continually(
            server.read()
          )
          .takeWhile(_ != -1)
          .toList

        assert(result == values.map(_ & 0xff))
      }
    }
  }
}
