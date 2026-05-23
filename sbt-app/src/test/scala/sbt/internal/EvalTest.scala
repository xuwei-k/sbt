package sbt.internal

import dotty.tools.dotc.reporting.ConsoleReporter

import java.nio.charset.StandardCharsets
import sbt.io.IO

object EvalTest extends verify.BasicTestSuite:
  test("hoho") {
    IO.withTemporaryDirectory { tmp =>
      val out = new java.io.ByteArrayOutputStream()
      val reporter = ForwardingReporter(
        ConsoleReporter(
          writer = new java.io.PrintWriter(out)
        )
      )
      val eval = sbt.internal.Eval(
        nonCpOptions = Seq("-deprecation"),
        backingDir = tmp.toPath,
        mkReporter = () => reporter
      )
      val res = eval.eval(
        """|version := "1"
           |
           |version := "2"
           |
           |version := "3"
           |
           |version := "4"
           |
           |version := "5"
           |
           |InputKey[Int]("a") := {
           |  Stream("a").size
           |}
           |
           |dependencyOverrides ++= Seq(
           |)
           |
           |TaskKey[Int]("b") := {
           |  Stream("b").size
           |}
           |""".stripMargin,
        EvalImports(Seq("import sbt._", "import sbt.Keys._")),
        None,
        "build.sbt",
        0,
      )
      println(new String(out.toByteArray, StandardCharsets.UTF_8))
    }
  }
end EvalTest
