package sbt.internal

import org.scalatest.freespec.AnyFreeSpec

import java.nio.file.Files
import java.nio.file.Paths

class BootServerSocketTest extends AnyFreeSpec {
  "BootServerSocket" - {
    "newUnixDomainSocket" in {
      if (!scala.util.Properties.isWin) {
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
          } finally {
            serverSocket.close()
          }
        } finally {
          Files.deleteIfExists(Paths.get(path))
          Files.deleteIfExists(dir)
        }
      }
    }
  }
}
