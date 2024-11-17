import sbt.*
import sbt.Keys.*

object ImplicitKeyword extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  object autoImport {
    val implicitKeyword = taskKey[Int]("")
    val allImplicitKeyword = taskKey[Seq[(String, Int)]]("")
  }
  import autoImport.*

  override def globalSettings: Seq[Def.Setting[?]] = Def.settings(
    allImplicitKeyword := Def.taskDyn {
      val s = state.value
      val extracted = Project.extract(s)
      val currentBuildUri = extracted.currentRef.build
      val projects = extracted.structure.units
        .apply(currentBuildUri)
        .defined
        .values
        .filterNot(_.id.contains("Scalafix"))
        .toList
      Def.task {
        val x1: Seq[(String, Int)] =
          projects
            .map(_.id)
            .zip(projects.map(p => LocalProject(p.id) / Compile / implicitKeyword).join.value)
        val x2: Seq[(String, Int)] =
          projects
            .map(_.id)
            .zip(projects.map(p => LocalProject(p.id) / Test / implicitKeyword).join.value)
        val result =
          x1.map(x => x.copy(_1 = x._1 + "-main")) ++ x2.map(x => x.copy(_1 = x._1 + "-test"))
        val res = result.sortBy(_._2).filter(_._2 > 0)
        res.foreach(println)
        println("all = " + res.map(_._2).sum)
        res
      }
    }.value
  )
  override def projectSettings: Seq[Def.Setting[?]] = Def.settings(
    Seq(Compile, Test).map { x =>
      (x / implicitKeyword) := {
        import scala.meta.*
        ((x / unmanagedSources).value ** "*.scala")
          .get()
          .map { file =>
            val input = Input.File(file)
            val tree = implicitly[parsers.Parse[Source]].apply(input, dialects.Scala3).get
            val n = tree.tokens.collect { case _: tokens.Token.KwImplicit =>
              ()
            }.size
            if (n > 0) {
              IO.relativize((LocalRootProject / baseDirectory).value, file).foreach { p =>
                println((p, n))
              }
            }
            n
          }
          .sum
      }
    }
  )
}
