package sbt.internal

import sbt.internal.parser.AbstractSpec
import sbt.internal.inc.PlainVirtualFile
import sbt.io.IO

import java.nio.file.Paths
import java.nio.file.Path
import java.io.File
import java.net.URLClassLoader

object EvaluateConfigurationsSpec extends AbstractSpec {
  private def getCclasspath(): Seq[Path] = {
    def loop(loader: ClassLoader, acc: List[Path]): Seq[Path] = {
      println(loader.getClass.getName)
      loader match {
        case x: URLClassLoader if x.getClass.getName != "sbt.internal.ScalaLibraryClassLoader" =>
          loop(
            loader.getParent,
            x.getURLs
              .map(_.toURI)
              .map(Paths.get)
              .toList ::: acc
          )
        case _ =>
          acc.distinct
      }
    }
    loop(this.getClass.getClassLoader, Nil)
  }

  val imports = List(
    ".sbt.*",
    ".sbt.given",
    ".sbt.BareBuildSyntax.*",
    ".sbt.Keys.*",
    ".sbt.nio.Keys.*",
    ".sbt.util.CacheImplicits.given",
    ".sbt.librarymanagement.LibraryManagementCodec.given",
  ).map("import _root_." + _)

  test("Specific error message for defining types") {
    // https://github.com/sbt/sbt/issues/9566

    val classpath = getCclasspath()

    IO.withTemporaryDirectory { tmpDir =>
      val sbtFile = new File(tmpDir, "my-build.sbt")
      IO.write(
        sbtFile,
        """|sbt.Keys.name := "foo"
           |
           |""".stripMargin
      )
      val res = EvaluateConfigurations.evaluateConfiguration(
        new Eval(Nil, classpath, None, None),
        PlainVirtualFile(sbtFile.toPath),
        imports
      )
      println(res)
    }
  }
}
