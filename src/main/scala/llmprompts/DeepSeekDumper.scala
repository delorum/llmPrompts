package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.{Instant, ZoneOffset}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters._
import scala.collection.mutable
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal

final case class DeepSeekPrompt(conversationId: String, text: String, timestamp: String)
final case class DeepSeekExport(promptCount: Int, prompts: Vector[CombinedPrompt])

object DeepSeekDumper {
  val DeepSeekDirectoryName = "deepseek"
  private val ArchiveName = raw"deepseek_data-\d{4}-\d{2}-\d{2}\.zip".r

  def dump(conversationsFile: Path, output: Path): Int = {
    dumpWithPrompts(conversationsFile, output, TimestampFormatter.MoscowOffset).promptCount
  }

  def dumpWithPrompts(
      conversationsFile: Path, output: Path, timezoneOffset: ZoneOffset): DeepSeekExport =
    dumpJson(Files.readString(conversationsFile, StandardCharsets.UTF_8), output, timezoneOffset)

  def dumpArchive(archive: Path, output: Path): Int = {
    dumpArchiveWithPrompts(archive, output, TimestampFormatter.MoscowOffset).promptCount
  }

  def dumpArchiveWithPrompts(archive: Path, output: Path, timezoneOffset: ZoneOffset): DeepSeekExport = {
    dumpPrompts(readArchivePrompts(archive), output, timezoneOffset)
  }

  def dumpDirectoryWithPrompts(directory: Path, output: Path, timezoneOffset: ZoneOffset): DeepSeekExport = {
    val archives = Using.resource(Files.list(directory)) { paths =>
      paths.iterator.asScala.filter(Files.isRegularFile(_)).filter { path =>
        ArchiveName.pattern.matcher(path.getFileName.toString).matches()
      }.toVector.sortBy(_.getFileName.toString)
    }
    require(archives.nonEmpty,
      s"DeepSeek conversations directory contains no deepseek_data-YYYY-MM-DD.zip archives: $directory")
    val prompts = archives.flatMap(readArchivePrompts)
      .distinctBy(prompt => (prompt.conversationId, prompt.timestamp, prompt.text))
    dumpPrompts(prompts, output, timezoneOffset)
  }

  private def readArchivePrompts(archive: Path): Vector[DeepSeekPrompt] = {
    val result = try {
      Using.resource(new ZipFile(archive.toFile)) { zip =>
        val entries = zip.entries.asScala.filterNot(_.isDirectory)
          .filter(entry => entry.getName == "conversations.json" || entry.getName.endsWith("/conversations.json"))
          .toVector
        if (entries.isEmpty) Left("conversations.json was not found")
        else if (entries.size > 1) Left(s"found ${entries.size} conversations.json files instead of one")
        else {
          val json = Using.resource(zip.getInputStream(entries.head)) { input =>
            new String(input.readAllBytes(), StandardCharsets.UTF_8)
          }
          parseLenient(json)
        }
      }
    } catch {
      case NonFatal(error) => Left(s"cannot read archive or JSON: ${error.getMessage}")
    }
    result match {
      case Left(reason) =>
        Console.err.println(s"Skipping DeepSeek archive $archive: $reason")
        Vector.empty
      case Right((prompts, warnings)) =>
        warnings.toVector.sortBy(_._1).foreach { case (reason, count) =>
          Console.err.println(s"DeepSeek archive $archive: skipped $count item(s): $reason")
        }
        prompts
    }
  }

  private def dumpJson(json: String, output: Path, timezoneOffset: ZoneOffset): DeepSeekExport = {
    dumpPrompts(parse(json), output, timezoneOffset)
  }

  private def dumpPrompts(
      sourcePrompts: Vector[DeepSeekPrompt], output: Path, timezoneOffset: ZoneOffset): DeepSeekExport = {
    val prompts = sourcePrompts.sortBy(prompt => parseTimestamp(prompt.timestamp))
    val directory = output.resolve(DeepSeekDirectoryName)
    Files.createDirectories(directory)
    val summary = prompts.groupBy(_.conversationId).toVector
      .sortBy { case (_, conversationPrompts) => parseTimestamp(conversationPrompts.map(_.timestamp).min) }
      .map { case (conversationId, conversationPrompts) =>
        s"$conversationId: ${conversationPrompts.size} промтов"
      }.mkString("\n")
    val listing = prompts.map { prompt =>
      val timestamp = TimestampFormatter.format(prompt.timestamp, timezoneOffset)
      s"Сессия: ${prompt.conversationId}\nВремя запроса: $timestamp\n${prompt.text}"
    }.mkString("\n\n")
    val body = s"Сессии:\n$summary\n\nПромты:\n$listing"
    Files.writeString(directory.resolve("all-prompts.txt"), body, StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    DeepSeekExport(prompts.size, prompts.map { prompt =>
      CombinedPrompt("deepseek", prompt.conversationId, None, prompt.text, Some(prompt.timestamp))
    })
  }

  private[llmprompts] def parse(conversationsFile: Path): Vector[DeepSeekPrompt] = {
    parse(Files.readString(conversationsFile, StandardCharsets.UTF_8))
  }

  private def parse(json: String): Vector[DeepSeekPrompt] = {
    val root = ujson.read(json)
    root.arr.toVector.flatMap { conversation =>
      val conversationId = string(conversation.obj.get("id"))
      val nodes = conversation.obj.get("mapping").collect { case mapping: ujson.Obj => mapping }
      for {
        id <- conversationId.toVector
        mapping <- nodes.toVector
        node <- mapping.value.values.toVector
        message <- node.obj.get("message").collect { case value: ujson.Obj => value }.toVector
        timestamp <- string(message.value.get("inserted_at")).toVector
        fragments <- message.value.get("fragments").collect { case value: ujson.Arr => value }.toVector
        fragment <- fragments.value.toVector
        if string(fragment.obj.get("type")).contains("REQUEST")
        content <- string(fragment.obj.get("content")).filter(_.nonEmpty).toVector
      } yield DeepSeekPrompt(id, content, timestamp)
    }
  }

  private def parseLenient(json: String): Either[String, (Vector[DeepSeekPrompt], Map[String, Int])] = {
    val root = try ujson.read(json) catch {
      case NonFatal(error) => return Left(s"invalid conversations.json: ${error.getMessage}")
    }
    val conversations = root match {
      case array: ujson.Arr => array.value.toVector
      case _ => return Left("unsupported conversations.json format: expected a JSON array")
    }
    val warnings = mutable.Map.empty[String, Int].withDefaultValue(0)
    def warn(reason: String): Unit = warnings.update(reason, warnings(reason) + 1)

    val prompts = conversations.flatMap {
      case conversation: ujson.Obj =>
        val conversationId = string(conversation.value.get("id"))
        val mapping = conversation.value.get("mapping").collect { case value: ujson.Obj => value }
        (conversationId, mapping) match {
          case (Some(id), Some(nodes)) => nodes.value.values.toVector.flatMap {
            case node: ujson.Obj => node.value.get("message") match {
              case Some(message: ujson.Obj) =>
                val timestamp = string(message.value.get("inserted_at"))
                message.value.get("fragments") match {
                  case Some(fragments: ujson.Arr) => fragments.value.toVector.flatMap {
                    case fragment: ujson.Obj if string(fragment.value.get("type")).contains("REQUEST") =>
                      (timestamp, string(fragment.value.get("content")).filter(_.nonEmpty)) match {
                        case (Some(time), Some(content)) => Vector(DeepSeekPrompt(id, content, time))
                        case (None, _) => warn("REQUEST message has no string inserted_at"); Vector.empty
                        case (_, None) => warn("REQUEST fragment has no non-empty string content"); Vector.empty
                      }
                    case _: ujson.Obj => Vector.empty
                    case _ => warn("fragment is not a JSON object"); Vector.empty
                  }
                  case _ => warn("message has no fragments array"); Vector.empty
                }
              case Some(_) => warn("message is not a JSON object"); Vector.empty
              case None => Vector.empty
            }
            case _ => warn("mapping node is not a JSON object"); Vector.empty
          }
          case _ => warn("conversation has no string id or mapping object"); Vector.empty
        }
      case _ => warn("conversation is not a JSON object"); Vector.empty
    }
    Right(prompts, warnings.toMap)
  }

  private def parseTimestamp(value: String): Instant =
    Try(Instant.parse(value)).getOrElse(Instant.MAX)

  private def string(value: Option[ujson.Value]): Option[String] =
    value.collect { case ujson.Str(text) => text }
}
