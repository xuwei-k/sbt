/*
 * sbt
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under BSD-3-Clause license (see LICENSE)
 */

package sbt
package protocol

import sjsonnew.{ JsonFormat, JsonWriter }
import sjsonnew.support.scalajson.unsafe.{ Parser, Converter, CompactPrinter }
import sjsonnew.shaded.scalajson.ast.unsafe.{ JValue, JObject, JString }
import java.nio.ByteBuffer
import java.util.UUID
import scala.util.{ Success, Failure }
import sbt.internal.util.StringEvent
import sbt.internal.protocol.{
  JsonRpcMessage,
  JsonRpcRequestMessage,
  JsonRpcResponseMessage,
  JsonRpcNotificationMessage
}

object Serialization {
  private[sbt] val VsCode = "application/vscode-jsonrpc; charset=utf-8"

  def serializeEvent[A: JsonFormat](event: A): Array[Byte] = {
    val json: JValue = Converter.toJson[A](event).get
    CompactPrinter(json).getBytes("UTF-8")
  }

  def serializeCommand(command: CommandMessage): Array[Byte] = {
    import codec.JsonProtocol._
    val json: JValue = Converter.toJson[CommandMessage](command).get
    CompactPrinter(json).getBytes("UTF-8")
  }

  private[sbt] def serializeCommandAsJsonMessage(command: CommandMessage): String = {
    import sjsonnew.BasicJsonProtocol._

    command match {
      case x: InitCommand =>
        val execId = x.execId.getOrElse(UUID.randomUUID.toString)
        val opt = x.token match {
          case Some(t) =>
            val json: JValue = Converter.toJson[String](t).get
            val v = CompactPrinter(json)
            s"""{ "token": $v }"""
          case None => "{}"
        }
        s"""{ "jsonrpc": "2.0", "id": "$execId", "method": "initialize", "params": { "initializationOptions": $opt } }"""
      case x: ExecCommand =>
        val execId = x.execId.getOrElse(UUID.randomUUID.toString)
        val json: JValue = Converter.toJson[String](x.commandLine).get
        val v = CompactPrinter(json)
        s"""{ "jsonrpc": "2.0", "id": "$execId", "method": "sbt/exec", "params": { "commandLine": $v } }"""
      case x: SettingQuery =>
        val execId = UUID.randomUUID.toString
        val json: JValue = Converter.toJson[String](x.setting).get
        val v = CompactPrinter(json)
        s"""{ "jsonrpc": "2.0", "id": "$execId", "method": "sbt/setting", "params": { "setting": $v } }"""
    }
  }

  def serializeEventMessage(event: EventMessage): Array[Byte] = {
    import codec.JsonProtocol._
    val json: JValue = Converter.toJson[EventMessage](event).get
    CompactPrinter(json).getBytes("UTF-8")
  }

  /** This formats the message according to JSON-RPC. http://www.jsonrpc.org/specification */
  private[sbt] def serializeResponseMessage(message: JsonRpcResponseMessage): Array[Byte] = {
    import sbt.internal.protocol.codec.JsonRPCProtocol._
    serializeResponse(message)
  }

  /** This formats the message according to JSON-RPC. http://www.jsonrpc.org/specification */
  private[sbt] def serializeNotificationMessage(
      message: JsonRpcNotificationMessage,
  ): Array[Byte] = {
    import sbt.internal.protocol.codec.JsonRPCProtocol._
    serializeResponse(message)
  }

  private[sbt] def serializeResponse[A: JsonWriter](message: A): Array[Byte] = {
    val json: JValue = Converter.toJson[A](message).get
    val body = CompactPrinter(json)
    val bodyLength = body.getBytes("UTF-8").length

    Iterator(
      s"Content-Length: $bodyLength",
      s"Content-Type: $VsCode",
      "",
      body
    ).mkString("\r\n").getBytes("UTF-8")
  }

  /**
   * @return A command or an invalid input description
   */
  def deserializeCommand(bytes: Seq[Byte]): Either[String, CommandMessage] = {
    val buffer = ByteBuffer.wrap(bytes.toArray)
    Parser.parseFromByteBuffer(buffer) match {
      case Success(json) =>
        import codec.JsonProtocol._
        Converter.fromJson[CommandMessage](json) match {
          case Success(command) => Right(command)
          case Failure(e)       => Left(e.getMessage)
        }
      case Failure(e) =>
        Left(s"Parse error: ${e.getMessage}")
    }
  }

  /**
   * @return A command or an invalid input description
   */
  def deserializeEvent(bytes: Seq[Byte]): Either[String, Any] = {
    val buffer = ByteBuffer.wrap(bytes.toArray)
    Parser.parseFromByteBuffer(buffer) match {
      case Success(json) =>
        detectType(json) match {
          case Some("StringEvent") =>
            import sbt.internal.util.codec.JsonProtocol._
            Converter.fromJson[StringEvent](json) match {
              case Success(event) => Right(event)
              case Failure(e)     => Left(e.getMessage)
            }
          case _ =>
            import codec.JsonProtocol._
            Converter.fromJson[EventMessage](json) match {
              case Success(event) => Right(event)
              case Failure(e)     => Left(e.getMessage)
            }
        }
      case Failure(e) =>
        Left(s"Parse error: ${e.getMessage}")
    }
  }

  def detectType(json: JValue): Option[String] =
    json match {
      case JObject(fields) =>
        (fields find { _.field == "type" } map { _.value }) match {
          case Some(JString(value)) => Some(value)
          case _                    => None
        }
      case _ => None
    }

  /**
   * @return A command or an invalid input description
   */
  def deserializeEventMessage(bytes: Seq[Byte]): Either[String, EventMessage] = {
    val buffer = ByteBuffer.wrap(bytes.toArray)
    Parser.parseFromByteBuffer(buffer) match {
      case Success(json) =>
        import codec.JsonProtocol._
        Converter.fromJson[EventMessage](json) match {
          case Success(event) => Right(event)
          case Failure(e)     => Left(e.getMessage)
        }
      case Failure(e) =>
        Left(s"Parse error: ${e.getMessage}")
    }
  }

  private[sbt] def deserializeJsonMessage(bytes: Seq[Byte]): Either[String, JsonRpcMessage] = {
    val buffer = ByteBuffer.wrap(bytes.toArray)
    Parser.parseFromByteBuffer(buffer) match {
      case Success(json @ JObject(fields)) =>
        import sbt.internal.protocol.codec.JsonRPCProtocol._
        if (fields exists { _.field == "method" }) {
          if (fields exists { _.field == "id" })
            Converter.fromJson[JsonRpcRequestMessage](json) match {
              case Success(request) => Right(request)
              case Failure(e)       => Left(s"Conversion error: ${e.getMessage}")
            } else
            Converter.fromJson[JsonRpcNotificationMessage](json) match {
              case Success(notification) => Right(notification)
              case Failure(e)            => Left(s"Conversion error: ${e.getMessage}")
            }
        } else
          Converter.fromJson[JsonRpcResponseMessage](json) match {
            case Success(res) => Right(res)
            case Failure(e)   => Left(s"Conversion error: ${e.getMessage}")
          }
      case Success(json) =>
        Left(s"Expected JSON object but found $json")
      case Failure(e) =>
        Left(s"Parse error: ${e.getMessage}")
    }
  }

  private[sbt] def compactPrintJsonOpt(jsonOpt: Option[JValue]): String = {
    jsonOpt match {
      case Some(x) => CompactPrinter(x)
      case _       => ""
    }
  }
}
