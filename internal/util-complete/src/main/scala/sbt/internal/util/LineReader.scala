/*
 * sbt
 * Copyright 2023, Scala center
 * Copyright 2011 - 2022, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal.util

import java.io.*
import java.util.List as JList

import org.jline.reader.{
  Candidate,
  Completer,
  EndOfFileException,
  LineReader as JLineReader,
  LineReaderBuilder,
  ParsedLine,
  UserInterruptException,
}
import org.jline.utils.ClosedException
import sbt.internal.util.complete.Parser
import sbt.io.syntax.*

import scala.util.control.NonFatal
import java.nio.channels.ClosedByInterruptException
import java.net.MalformedURLException

import org.jline.builtins.InputRC

trait LineReader extends AutoCloseable:
  def readLine(prompt: String, mask: Option[Char] = None): Option[String]
  override def close(): Unit = {}

object LineReader:
  val HandleCONT =
    !java.lang.Boolean.getBoolean("sbt.disable.cont") && Signals.supported(Signals.CONT)
  val MaxHistorySize = 500

  private def completer(parser: Parser[?]): Completer = new Completer:
    def complete(lr: JLineReader, pl: ParsedLine, candidates: JList[Candidate]): Unit =
      Parser.completions(parser, pl.line(), 10).get.foreach { c =>
        /*
         * For commands like `~` that delegate parsing to another parser, the `~` may be
         * excluded from the completion result. For example,
         * ~testOnly <TAB>
         * might return results like
         * 'testOnly ;'
         * 'testOnly com.foo.FooSpec'
         * ...
         * If we use the raw display, JLine will reject the completions because they are
         * missing the leading `~`. To workaround this, we append to the result to the
         * line provided the line does not end with " ". This fixes the missing `~` in
         * the prefix problem. We also need to split the line on space and take the
         * last token and append to that otherwise the completion will double print
         * the prefix, so that `testOnly com<Tab>` might expand to something like:
         * `testOnly testOnly\ com.foo.FooSpec` instead of `testOnly com.foo.FooSpec`.
         */
        if c.append.nonEmpty then
          val cand = pl.line() match
            case line if line.endsWith(" ") => c.append
            case line                       => line.split(" ").last + c.append
          // https://github.com/jline/jline3/blob/9a4971868e4bdd29a36e454de01f54d3cd6071e0/reader/src/main/java/org/jline/reader/Candidate.java#L123-L131
          // "If the candidate is complete and is selected, a space separator will be added."
          val complete = false
          candidates.add(new Candidate(cand, cand, null, null, null, null, complete))
      }
  private def inputrcFileUrl(): Option[URL] =
    // keep jline2 compatibility
    // https://github.com/jline/jline2/blob/12b98d94589e3bd6a6/src/main/java/jline/console/ConsoleReader.java#L291-L306
    sys.props
      .get("jline.inputrc")
      .flatMap { path =>
        try Some(url(path))
        catch
          case _: MalformedURLException =>
            Some(file(path).toURI.toURL)
      }
      .orElse {
        sys.props.get("user.home").map { home =>
          val f = file(home) / ".inputrc"
          (if f.isFile then f else file("/etc/inputrc")).toURI.toURL
        }
      }
  // cache on memory.
  private lazy val inputrcFileContents: Option[Array[Byte]] =
    inputrcFileUrl().map(in => sbt.io.IO.readBytes(in.openStream()))
  def createReader(
      historyPath: Option[File],
      parser: Parser[?],
      terminal: Terminal,
  ): LineReader =
    // We may want to consider in-sourcing LineReader.java from jline. We don't otherwise
    // directly need jline3 for sbt.
    new LineReader:
      override def readLine(prompt: String, mask: Option[Char]): Option[String] =
        val term = JLine3(terminal)
        val reader = LineReaderBuilder
          .builder()
          .terminal(term)
          .completer(completer(parser))
          .option(JLineReader.Option.CASE_INSENSITIVE, true)
          .build()
        try
          inputrcFileContents.foreach { bytes =>
            InputRC.configure(
              reader,
              new ByteArrayInputStream(bytes)
            )
          }
        catch
          case NonFatal(_) =>
          // ignore
        historyPath.foreach(f => reader.setVariable(JLineReader.HISTORY_FILE, f))
        val signalRegistration = terminal match
          case _: Terminal.ConsoleTerminal => Some(Signals.register(() => terminal.write(-1)))
          case _                           => None
        try
          terminal.withRawInput {
            Option(mask.map(reader.readLine(prompt, _)).getOrElse(reader.readLine(prompt)))
          }
        catch
          case e: EndOfFileException =>
            if terminal == Terminal.console && !Terminal.hasConsole then None
            else Some("exit")
          case _: IOError | _: ClosedException => Some("exit")
          case _: UserInterruptException | _: ClosedByInterruptException |
              _: UncheckedIOException =>
            throw new InterruptedException
        finally
          signalRegistration.foreach(_.remove())
          terminal.prompt.reset()
          term.close()
      end readLine

end LineReader

abstract class JLine extends LineReader:
  protected def handleCONT: Boolean

  private def handleMultilinePrompt(prompt: String): String =
    val lines0 = """\r?\n""".r.split(prompt)
    lines0.length match
      case 0 | 1 => handleProgress(prompt)
      case _     =>
        val lines = lines0.toList map handleProgress
        // Workaround for regression jline/jline2#205
        lines.last

  private def handleProgress(prompt: String): String =
    import ConsoleAppender.*
    if showProgress then s"$DeleteLine" + prompt
    else prompt

  private def resume(): Unit =
    Terminal.reset()
end JLine
