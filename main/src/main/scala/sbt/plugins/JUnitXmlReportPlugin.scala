/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt
package plugins

import java.io.File

import Def.{ Setting, settingKey }
import Defaults.*
import Keys.*
import KeyRanks.*
import sbt.ProjectExtra.inConfig
import sbt.internal.*
import sbt.io.syntax.*
import sbt.librarymanagement.Configurations.{ IntegrationTest, Test }
import scala.annotation.nowarn

/**
 * An experimental plugin that adds the ability for junit-xml to be generated.
 *
 *  To disable this plugin, you need to add:
 *  {{{
 *     val myProject = project in file(".") disablePlugins (plugins.JunitXmlReportPlugin)
 *  }}}
 *
 *  Note:  Using AutoPlugins to enable/disable build features is experimental in sbt 0.13.5.
 */
object JUnitXmlReportPlugin extends AutoPlugin {
  // TODO - If testing becomes its own plugin, we only rely on the core settings.
  override def requires = JvmPlugin
  override def trigger = allRequirements

  object autoImport {
    val testReportsDirectory =
      settingKey[File]("Directory for outputting junit test reports.").withRank(AMinusSetting)

    lazy val testReportSettings: Seq[Setting[_]] = Seq(
      testReportsDirectory := target.value / (prefix(configuration.value.name) + "reports"),
      testListeners += new JUnitXmlTestsListener(
        testReportsDirectory.value,
        SysProp.legacyTestReport,
        streams.value.log
      )
    )
  }

  import autoImport.*

  @nowarn
  override lazy val projectSettings: Seq[Setting[_]] =
    inConfig(Test)(testReportSettings) ++
      inConfig(IntegrationTest)(testReportSettings)
}
