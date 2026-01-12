package sbt

import java.nio.file.{ FileAlreadyExistsException, Files }

import sbt.Keys.*
import sbt.internal.librarymanagement.IvyXml

/** This local plugin provides ways of publishing just the binary jar. */
object PublishBinPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val publishLocalBin = taskKey[Unit]("")
    val publishLocalBinConfig = taskKey[PublishConfiguration]("")
  }
  import autoImport.*

  private val dummyDoc = taskKey[File]("").withRank(Int.MaxValue)
  override val globalSettings = Seq(publishLocalBin := (()))

  override val projectSettings: Seq[Def.Setting[?]] = Def settings (
    publishLocalBin := Classpaths
      .publishOrSkip(publishLocalBinConfig, publishLocalBin / skip)
      .value,
    publishLocalBinConfig := publishLocalBinConfig
      .dependsOn(
        Def.task {
          val currentProject = {
            val proj = csrProject.value
            val publications = csrPublications.value
            proj.withPublications(publications)
          }
          IvyXml.writeFiles(currentProject, None, ivySbt.value, streams.value.log)
        }
      )
      .value,
    dummyDoc := {
      val dummyFile = streams.value.cacheDirectory / "doc.jar"
      try {
        Files.createDirectories(dummyFile.toPath.getParent)
        Files.createFile(dummyFile.toPath)
      } catch { case _: FileAlreadyExistsException => }
      dummyFile
    },
  )
}
