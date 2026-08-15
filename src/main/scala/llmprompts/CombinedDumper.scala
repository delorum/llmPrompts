package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.{Instant, ZoneOffset}
import scala.util.Try

final case class CombinedPrompt(
    llm: String,
    sessionId: String,
    workingDirectory: Option[String],
    text: String,
    timestamp: Option[String]
)

object CombinedDumper {
  def write(
      output: Path,
      prompts: Vector[CombinedPrompt],
      timezoneOffset: ZoneOffset = TimestampFormatter.MoscowOffset
  ): Unit = {
    Files.createDirectories(output)
    val ordered = prompts.sortBy { prompt =>
      prompt.timestamp.flatMap(value => Try(Instant.parse(value)).toOption).getOrElse(Instant.MAX)
    }
    val listing = ordered.map { prompt =>
      val workingDirectory = prompt.workingDirectory.map(path => s"Рабочая папка: $path\n").getOrElse("")
      val timestamp = prompt.timestamp
        .map(value => s"Время запроса: ${TimestampFormatter.format(value, timezoneOffset)}\n").getOrElse("")
      s"LLM: ${prompt.llm}\nСессия: ${prompt.sessionId}\n$workingDirectory$timestamp${prompt.text}"
    }.mkString("\n\n")
    Files.writeString(output.resolve("all-prompts.txt"), s"Промты:\n$listing", StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
  }
}
