/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package internal

import java.io.File
import Keys.{ organization, thisProject, autoGeneratedProject, publish, publishLocal, skip }
import Def.Setting
// import sbt.ProjectExtra.apply
import sbt.io.Hash
import sbt.internal.util.{ Attributed, StringAttributeMap }
import sbt.internal.inc.{ FileAnalysisStore, ReflectUtilities }
import xsbti.{ FileConverter, VirtualFileRef }
import xsbti.compile.CompileAnalysis

trait BuildDef {
  def projectDefinitions(@deprecated("unused", "") baseDirectory: File): Seq[Project] = projects
  def projects: Seq[Project] =
    CompositeProject.expand(ReflectUtilities.allValsC(this, classOf[CompositeProject]).values.toSeq)
  // TODO: Should we grab the build core settings here or in a plugin?
  def settings: Seq[Setting[_]] = Defaults.buildCore
  def buildLoaders: Seq[BuildLoader.Components] = Nil

  /**
   * Explicitly defines the root project.
   * If None, the root project is the first project in the build's root directory or just the first project if none are in the root directory.
   */
  def rootProject: Option[Project] = None
}

private[sbt] object BuildDef:
  val defaultEmpty: BuildDef = new BuildDef { override def projects = Nil }

  val default: BuildDef = new BuildDef {
    override def projectDefinitions(base: File) = defaultProject(defaultID(base), base) :: Nil
  }

  def defaultAggregated(id: String, aggregate: Seq[ProjectRef]): BuildDef = new BuildDef {
    override def projectDefinitions(base: File): Seq[Project] =
      defaultAggregatedProject(id, base, aggregate) :: Nil
  }

  def defaultID(base: File, prefix: String = "default"): String =
    prefix + "-" + Hash.trimHashString(base.getAbsolutePath, 6)

  def defaultProject(id: String, base: File): Project =
    Project(id, base).settings(defaultProjectSettings)

  def defaultAggregatedProject(id: String, base: File, agg: Seq[ProjectRef]): Project =
    defaultProject(id, base).aggregate(agg*)

  private[sbt] def generatedRootSkipPublish(
      id: String,
      base: File,
  ): Project =
    Project
      .mkGeneratedRoot(id, base, Nil)
      .settings(
        defaultProjectSettings,
        publish / skip := true,
        publishLocal / skip := true,
      )

  private[sbt] def defaultProjectSettings: Seq[Setting[_]] = Seq(
    // TODO - Can we move this somewhere else?  ordering of settings is causing this to get borked.
    // if the user has overridden the name, use the normal organization that is derived from the name.
    organization := {
      def isDefault(o: String) = thisProject.value.id == o
      organization.?.value match {
        case Some(o) if !isDefault(o) => o
        case _                        => "default"
      }
    },
    autoGeneratedProject := true,
  )

  def analyzed(
      in: Seq[Attributed[_]],
      converter: FileConverter
  ): Seq[xsbti.compile.CompileAnalysis] =
    in.flatMap(a => extractAnalysis(a.metadata, converter))

  private[sbt] def extractAnalysis(
      metadata: StringAttributeMap,
      converter: FileConverter
  ): Option[CompileAnalysis] =
    import sbt.OptionSyntax.*
    def asBinary(file: File) = FileAnalysisStore.binary(file).get.asScala
    def asText(file: File) = FileAnalysisStore.text(file).get.asScala
    for
      ref <- metadata.get(Keys.analysis)
      file = converter.toPath(VirtualFileRef.of(ref)).toFile
      content <- asBinary(file).orElse(asText(file))
    yield content.getAnalysis

end BuildDef
