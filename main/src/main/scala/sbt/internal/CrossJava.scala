/*
 * sbt
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under BSD-3-Clause license (see LICENSE)
 */

package sbt
package internal

import java.io.File
import scala.collection.immutable.ListMap
import scala.annotation.tailrec
import sbt.io.Path
import sbt.io.syntax._
import sbt.Cross._
import sbt.Def.{ ScopedKey, Setting }
import sbt.internal.util.complete.DefaultParsers._
import sbt.internal.util.AttributeKey
import sbt.internal.util.complete.{ DefaultParsers, Parser }
import sbt.internal.CommandStrings.{
  JavaCrossCommand,
  JavaSwitchCommand,
  javaCrossHelp,
  javaSwitchHelp
}

private[sbt] object CrossJava {
  // parses jabba style version number adopt@1.8
  def parseJavaVersion(version: String): JavaVersion = {
    def splitDot(s: String): Vector[Long] =
      Option(s) match {
        case Some(x) => x.split('.').toVector.filterNot(_ == "").map(_.toLong)
        case _       => Vector()
      }
    def splitAt(s: String): Vector[String] =
      Option(s) match {
        case Some(x) => x.split('@').toVector
        case _       => Vector()
      }
    splitAt(version) match {
      case Vector(vendor, rest) => JavaVersion(splitDot(rest), Option(vendor))
      case Vector(rest)         => JavaVersion(splitDot(rest), None)
      case _                    => sys.error(s"Invalid JavaVersion: $version")
    }
  }

  def lookupJavaHome(jv: String, mappings: Map[String, File]): File = {
    val ms = mappings map { case (k, v) => (JavaVersion(k), v) }
    lookupJavaHome(JavaVersion(jv), ms)
  }

  def lookupJavaHome(jv: JavaVersion, mappings: Map[JavaVersion, File]): File = {
    mappings.get(jv) match {
      case Some(dir) => dir

      // when looking for "10" it should match "openjdk@10"
      case None if jv.vendor.isEmpty =>
        val noVendors: Map[JavaVersion, File] = mappings map {
          case (k, v) => k.withVendor(None) -> v
        }
        noVendors.get(jv).getOrElse(javaHomeNotFound(jv, mappings))
      case _ => javaHomeNotFound(jv, mappings)
    }
  }

  private def javaHomeNotFound(version: JavaVersion, mappings: Map[JavaVersion, File]): Nothing = {
    sys.error(s"""Java home for $version was not found in $mappings
                 |
                 |use Global / javaHomes += JavaVersion("$version") -> file(...)""".stripMargin)
  }

  private[sbt] case class SwitchTarget(
      version: Option[JavaVersion],
      home: Option[File],
      force: Boolean
  )
  private case class SwitchJavaHome(target: SwitchTarget, verbose: Boolean, command: Option[String])

  private[sbt] def versionParser(knownVersions: Vector[String]): Parser[SwitchTarget] = {
    val javaVersion = {
      val vendor = (StringBasic.filter(_.nonEmpty, _ => "empty vendor name") <~ "@").?
      val v = rep1sep(NatBasic, ".")
      token(vendor ~ v, "java version").examples(knownVersions: _*) ~ "!".?
    }
    val javaHome =
      token(StringBasic, "java home directory").filter(_.nonEmpty, s => "empty java home directory")

    (javaVersion || javaHome).map {
      case Left(((vendor, v), bang)) =>
        val force = bang.isDefined
        val versionArg = v.map(_.toLong).toVector
        SwitchTarget(Option(JavaVersion(versionArg, vendor)), None, force)
      case Right(home) =>
        SwitchTarget(None, Option(new File(home)), true)
    }
  }

  private def switchParser(state: State): Parser[SwitchJavaHome] = {
    import DefaultParsers._
    def versionAndCommand(spacePresent: Boolean) = {
      val x = Project.extract(state)
      import x._
      val javaHomes = getJavaHomesTyped(x, currentRef)
      val knownVersions = javaHomes.keysIterator.map(_.numberStr).toVector
      val version = versionParser(knownVersions)
      val spacedVersion =
        if (spacePresent) version
        else version & spacedFirst(JavaSwitchCommand)
      val verbose = Parser.opt(token(Space ~> "-v"))
      val optionalCommand = Parser.opt(token(Space ~> matched(state.combinedParser)))
      (spacedVersion ~ verbose ~ optionalCommand).map {
        case v ~ verbose ~ command =>
          SwitchJavaHome(v, verbose.isDefined, command)
      }
    }
    token(JavaSwitchCommand ~> OptSpace) flatMap { sp =>
      versionAndCommand(sp.nonEmpty)
    }
  }

  private def getJavaHomes(
      extracted: Extracted,
      proj: ResolvedReference
  ): Map[String, File] = {
    import extracted._
    (Keys.fullJavaHomes in proj get structure.data).get
  }

  private def getJavaHomesTyped(
      extracted: Extracted,
      proj: ResolvedReference
  ): Map[JavaVersion, File] = {
    getJavaHomes(extracted, proj) map { case (k, v) => (JavaVersion(k), v) }
  }

  private def getCrossJavaVersions(
      extracted: Extracted,
      proj: ResolvedReference
  ): Seq[String] = {
    import extracted._
    import Keys._
    (crossJavaVersions in proj get structure.data).getOrElse(Nil)
  }

  private def getCrossJavaHomes(extracted: Extracted, proj: ResolvedReference): Seq[File] = {
    import extracted._
    import Keys._
    val fjh = (fullJavaHomes in proj get structure.data).get
    (crossJavaVersions in proj get structure.data) map { jvs =>
      jvs map { jv =>
        lookupJavaHome(jv, fjh)
      }
    } getOrElse Vector()
  }

  private def switchCommandImpl(state: State, switch: SwitchJavaHome): State = {
    val extracted = Project.extract(state)
    import extracted._
    import Keys.javaHome

    // filter out subprojects based on switch target e.g. "10" vs what's in crossJavaVersions
    // for the subproject. Only if crossJavaVersions is non-empty, and does NOT include "10"
    // it will skip the subproject.
    val projects: Seq[(ResolvedReference, Seq[String])] = {
      val projectJavaVersions =
        structure.allProjectRefs.map(proj => proj -> getCrossJavaVersions(extracted, proj))
      if (switch.target.force) projectJavaVersions
      else
        switch.target.version match {
          case None => projectJavaVersions
          case Some(v) =>
            projectJavaVersions flatMap {
              case (proj, versions) =>
                if (versions.isEmpty || versions.contains[String](v.toString))
                  Vector(proj -> versions)
                else Vector()
            }
        }
    }

    def setJavaHomeForProjects: State = {
      val newSettings = projects.flatMap {
        case (proj, javaVersions) =>
          val fjh = getJavaHomesTyped(extracted, proj)
          val home = switch.target match {
            case SwitchTarget(Some(v), _, _) => lookupJavaHome(v, fjh)
            case SwitchTarget(_, Some(h), _) => h
            case _                           => sys.error(s"unexpected ${switch.target}")
          }
          val scope = Scope(Select(proj), Zero, Zero, Zero)
          Seq(
            javaHome in scope := Some(home)
          )
      }

      val filterKeys: Set[AttributeKey[_]] = Set(javaHome).map(_.key)

      val projectsContains: Reference => Boolean = projects.map(_._1).toSet[Reference].contains(_)

      // Filter out any old javaHome version settings that were added, this is just for hygiene.
      val filteredRawAppend = session.rawAppend.filter(_.key match {
        case ScopedKey(Scope(Select(ref), Zero, Zero, Zero), key)
            if filterKeys.contains(key) && projectsContains(ref) =>
          false
        case _ => true
      })

      val newSession = session.copy(rawAppend = filteredRawAppend ++ newSettings)

      BuiltinCommands.reapply(newSession, structure, state)
    }

    setJavaHomeForProjects
  }

  def switchJavaHome: Command =
    Command.arb(requireSession(switchParser), javaSwitchHelp)(switchCommandImpl)

  def crossJavaHome: Command =
    Command.arb(requireSession(crossParser), javaCrossHelp)(crossJavaHomeCommandImpl)

  private case class CrossArgs(command: String, verbose: Boolean)

  /**
   * Parse the given command into either an aggregate command or a command for a project
   */
  private def crossParser(state: State): Parser[CrossArgs] =
    token(JavaCrossCommand <~ OptSpace) flatMap { _ =>
      (token(Parser.opt("-v" <~ Space)) ~ token(matched(state.combinedParser))).map {
        case (verbose, command) => CrossArgs(command, verbose.isDefined)
      } & spacedFirst(JavaCrossCommand)
    }

  private def crossJavaHomeCommandImpl(state: State, args: CrossArgs): State = {
    val x = Project.extract(state)
    import x._
    val (aggs, aggCommand) = Cross.parseSlashCommand(x)(args.command)
    val projCrossVersions = aggs map { proj =>
      proj -> getCrossJavaHomes(x, proj)
    }
    // if we support javaHome, projVersions should be cached somewhere since
    // running ++2.11.1 is at the root level is going to mess with the scalaVersion for the aggregated subproj
    val projVersions = (projCrossVersions flatMap {
      case (proj, versions) => versions map { proj.project -> _ }
    }).toList

    val verbose = ""
    // println(s"projVersions $projVersions")

    if (projVersions.isEmpty) {
      state
    } else {
      // Detect whether a task or command has been issued
      val allCommands = Parser.parse(aggCommand, Act.aggregatedKeyParser(x)) match {
        case Left(_) =>
          // It's definitely not a task, check if it's a valid command, because we don't want to emit the warning
          // message below for typos.
          val validCommand = Parser.parse(aggCommand, state.combinedParser).isRight

          val distinctCrossConfigs = projCrossVersions.map(_._2.toSet).distinct
          if (validCommand && distinctCrossConfigs.size > 1) {
            state.log.warn(
              "Issuing a Java cross building command, but not all sub projects have the same cross build " +
                "configuration. This could result in subprojects cross building against Java versions that they are " +
                "not compatible with. Try issuing cross building command with tasks instead, since sbt will be able " +
                "to ensure that cross building is only done using configured project and Java version combinations " +
                "that are configured."
            )
            state.log.debug("Java versions configuration is:")
            projCrossVersions.foreach {
              case (project, versions) => state.log.debug(s"$project: $versions")
            }
          }

          // Execute using a blanket switch
          projCrossVersions.toMap.apply(currentRef).flatMap { version =>
            // Force scala version
            Seq(s"$JavaSwitchCommand $verbose $version!", aggCommand)
          }

        case Right(_) =>
          // We have a key, we're likely to be able to cross build this using the per project behaviour.

          // Group all the projects by scala version
          projVersions.groupBy(_._2).mapValues(_.map(_._1)).toSeq.flatMap {
            case (version, Seq(project)) =>
              // If only one project for a version, issue it directly
              Seq(s"$JavaSwitchCommand $verbose $version", s"$project/$aggCommand")
            case (version, projects) if aggCommand.contains(" ") =>
              // If the command contains a space, then the `all` command won't work because it doesn't support issuing
              // commands with spaces, so revert to running the command on each project one at a time
              s"$JavaSwitchCommand $verbose $version" :: projects
                .map(project => s"$project/$aggCommand")
            case (version, projects) =>
              // First switch scala version, then use the all command to run the command on each project concurrently
              Seq(
                s"$JavaSwitchCommand $verbose $version",
                projects.map(_ + "/" + aggCommand).mkString("all ", " ", "")
              )
          }
      }

      allCommands.toList ::: captureCurrentSession(state, x)
    }
  }

  private val JavaCapturedSession = AttributeKey[Seq[Setting[_]]]("javaCrossCapturedSession")

  private def captureCurrentSession(state: State, extracted: Extracted): State = {
    state.put(JavaCapturedSession, extracted.session.rawAppend)
  }

  def discoverJavaHomes: ListMap[String, File] = {
    ListMap(JavaDiscoverConfig.configs flatMap { _.javaHomes } sortWith (versionOrder): _*)
  }

  sealed trait JavaDiscoverConf {
    def javaHomes: Vector[(String, File)]
  }

  def versionOrder(left: (_, File), right: (_, File)): Boolean =
    versionOrder(left._2.getName, right._2.getName)

  // Sort version strings, considering 1.8.0 < 1.8.0_45 < 1.8.0_212
  @tailrec
  def versionOrder(left: String, right: String): Boolean = {
    val Pattern = """.*?([0-9]+)(.*)""".r
    left match {
      case Pattern(leftNumber, leftRest) =>
        right match {
          case Pattern(rightNumber, rightRest) =>
            if (Integer.parseInt(leftNumber) < Integer.parseInt(rightNumber)) true
            else if (Integer.parseInt(leftNumber) > Integer.parseInt(rightNumber)) false
            else versionOrder(leftRest, rightRest)
          case _ =>
            false
        }
      case _ =>
        true
    }
  }

  object JavaDiscoverConfig {
    class LinuxDiscoverConfig(base: File) extends JavaDiscoverConf {
      def candidates = wrapNull(base.list())
      val JavaHomeDir = """(java-|jdk)(1\.)?([0-9]+).*""".r
      def javaHomes: Vector[(String, File)] =
        candidates
          .collect {
            case dir @ JavaHomeDir(_, m, n) =>
              JavaVersion(nullBlank(m) + n).toString -> (base / dir)
          }
    }

    class MacOsDiscoverConfig extends JavaDiscoverConf {
      val base: File = file("/Library") / "Java" / "JavaVirtualMachines"
      val JavaHomeDir = """jdk-?(1\.)?([0-9]+).*""".r
      def javaHomes: Vector[(String, File)] =
        wrapNull(base.list())
          .collect {
            case dir @ JavaHomeDir(m, n) =>
              JavaVersion(nullBlank(m) + n).toString -> (base / dir / "Contents" / "Home")
          }
    }

    class JabbaDiscoverConfig extends JavaDiscoverConf {
      val base: File = Path.userHome / ".jabba" / "jdk"
      val JavaHomeDir = """([\w\-]+)\@(1\.)?([0-9]+).*""".r

      def javaHomes: Vector[(String, File)] =
        wrapNull(base.list())
          .collect {
            case dir @ JavaHomeDir(vendor, m, n) =>
              val v = JavaVersion(nullBlank(m) + n).withVendor(vendor).toString
              if ((base / dir / "Contents" / "Home").exists) v -> (base / dir / "Contents" / "Home")
              else v -> (base / dir)
          }
    }

    val configs = Vector(
      new JabbaDiscoverConfig,
      new LinuxDiscoverConfig(file("/usr") / "java"),
      new LinuxDiscoverConfig(file("/usr") / "lib" / "jvm"),
      new MacOsDiscoverConfig
    )
  }

  def nullBlank(s: String): String =
    if (s eq null) ""
    else s

  // expand Java versions to 1-20 to 1.x, and vice versa to accept both "1.8" and "8"
  private val oneDot = Map((1L to 20L).toVector flatMap { i =>
    Vector(Vector(i) -> Vector(1L, i), Vector(1L, i) -> Vector(i))
  }: _*)
  def expandJavaHomes(hs: Map[String, File]): Map[String, File] =
    hs flatMap {
      case (k, v) =>
        val jv = JavaVersion(k)
        if (oneDot.contains(jv.numbers))
          Vector(k -> v, jv.withNumbers(oneDot(jv.numbers)).toString -> v)
        else Vector(k -> v)
    }

  def wrapNull(a: Array[String]): Vector[String] =
    if (a eq null) Vector()
    else a.toVector
}
