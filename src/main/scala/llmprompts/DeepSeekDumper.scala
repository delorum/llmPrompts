package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.Using

final case class DeepSeekPrompt(conversationId: String, text: String, timestamp: String)

object DeepSeekDumper {
  val DeepSeekDirectoryName = "deepseek"

  def dump(conversationsFile: Path, output: Path): Int = {
    dumpJson(Files.readString(conversationsFile, StandardCharsets.UTF_8), output)
  }

  def dumpArchive(archive: Path, output: Path): Int = {
    val json = Using.resource(new ZipFile(archive.toFile)) { zip =>
      val entries = zip.entries.asScala.filterNot(_.isDirectory)
        .filter(entry => entry.getName == "conversations.json" || entry.getName.endsWith("/conversations.json"))
        .toVector
      require(entries.size == 1,
        s"DeepSeek archive must contain exactly one conversations.json, found ${entries.size}: $archive")
      Using.resource(zip.getInputStream(entries.head)) { input =>
        new String(input.readAllBytes(), StandardCharsets.UTF_8)
      }
    }
    dumpJson(json, output)
  }

  private def dumpJson(json: String, output: Path): Int = {
    val prompts = parse(json).sortBy(prompt => parseTimestamp(prompt.timestamp))
    val directory = output.resolve(DeepSeekDirectoryName)
    Files.createDirectories(directory)
    val summary = prompts.groupBy(_.conversationId).toVector
      .sortBy { case (_, conversationPrompts) => parseTimestamp(conversationPrompts.map(_.timestamp).min) }
      .map { case (conversationId, conversationPrompts) =>
        s"$conversationId: ${conversationPrompts.size} промтов"
      }.mkString("\n")
    val listing = prompts.map { prompt =>
      s"Сессия: ${prompt.conversationId}\nВремя запроса: ${prompt.timestamp}\n${prompt.text}"
    }.mkString("\n\n")
    val body = s"Сессии:\n$summary\n\nПромты:\n$listing"
    Files.writeString(directory.resolve("all-prompts.txt"), body, StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    prompts.size
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

  private def parseTimestamp(value: String): Instant =
    Try(Instant.parse(value)).getOrElse(Instant.MAX)

  private def string(value: Option[ujson.Value]): Option[String] =
    value.collect { case ujson.Str(text) => text }
}
