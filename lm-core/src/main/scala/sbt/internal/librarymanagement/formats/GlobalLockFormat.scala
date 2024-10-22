package sbt.internal.librarymanagement.formats

import sjsonnew.*
import xsbti.*
import java.io.File
import java.util.concurrent.Callable

/**
 * A fake JsonFormat for xsbti.GlobalLock.
 * This is mostly for making IvyConfiguration serializable to JSON.
 */
trait GlobalLockFormat { self: BasicJsonProtocol =>
  import GlobalLockFormats.*

  implicit lazy val globalLockIsoString: IsoString[GlobalLock] =
    IsoString.iso(_ => "<lock>", _ => NoGlobalLock)

  implicit lazy val GlobalLockFormat: JsonFormat[GlobalLock] = isoStringFormat(globalLockIsoString)
}

private[sbt] object GlobalLockFormats {
  object NoGlobalLock extends GlobalLock {
    def apply[T](lockFile: File, run: Callable[T]) = run.call()
  }
}
