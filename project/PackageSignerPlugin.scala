import sbt.*
import Keys.*
import sbt.internal.librarymanagement.IvyActions
import com.jsuereth.sbtpgp.SbtPgp
import com.typesafe.sbt.packager.universal.{ UniversalPlugin, UniversalDeployPlugin }
import com.typesafe.sbt.packager.debian.{ DebianPlugin, DebianDeployPlugin }
import com.typesafe.sbt.packager.rpm.{ RpmPlugin, RpmDeployPlugin }
import com.jsuereth.sbtpgp.gpgExtension

object PackageSignerPlugin extends sbt.AutoPlugin {
  override def trigger = allRequirements
  override def requires = SbtPgp && UniversalDeployPlugin && DebianDeployPlugin && RpmDeployPlugin

  import com.jsuereth.sbtpgp.PgpKeys.*
  import UniversalPlugin.autoImport.*
  import DebianPlugin.autoImport.*
  import RpmPlugin.autoImport.*

  override def projectSettings: Seq[Setting[?]] =
    inConfig(Universal)(packageSignerSettings) ++
      inConfig(Debian)(packageSignerSettings) ++
      inConfig(Rpm)(packageSignerSettings)

  def subExtension(art: Artifact, ext: String): Artifact =
    art.withExtension(ext)

  def packageSignerSettings: Seq[Setting[?]] = Seq(
    publishSigned := Def.taskDyn {
      val config = publishSignedConfiguration.value
      val s = streams.value
      Def.task {
        IvyActions.publish(ivyModule.value, config, s.log)
      }
    }.value,
    publishLocalSigned := Def.taskDyn {
      val config = publishLocalSignedConfiguration.value
      val s = streams.value
      Def.task {
        IvyActions.publish(ivyModule.value, config, s.log)
      }
    }.value
  )

}
