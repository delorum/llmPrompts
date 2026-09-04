package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, ZoneOffset}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal

object Main {
  def main(args: Array[String]): Unit = {
    if (args.nonEmpty) {
      Console.err.println("Usage: llm-prompt-dumper")
      sys.exit(2)
    }

    val config = AppConfig.load()
    val output = config.outputDirectory
    var exportedAny = false
    var combinedPrompts = Vector.empty[CombinedPrompt]
    config.codexSessionsDirectory.foreach { input =>
      require(Files.isDirectory(input), s"Codex sessions directory does not exist: $input")
      require(input != output && !input.startsWith(output), "Input and output directories must not overlap")
      val result = Dumper.dumpWithPrompts(input, output, config.timezoneOffset)
      combinedPrompts ++= result.prompts
      println(s"Exported ${result.newPromptCount} new Codex prompt(s) from $input; " +
        s"${result.promptCount} prompt(s) across ${result.sessionCount} session(s) total in " +
        output.resolve(Dumper.CodexDirectoryName))
      exportedAny = true
    }
    val deepSeekExport = config.deepSeekConversationsDirectory.map { directory =>
      require(Files.isDirectory(directory), s"DeepSeek conversations directory does not exist: $directory")
      directory -> DeepSeekDumper.dumpDirectoryWithPrompts(directory, output, config.timezoneOffset)
    }.orElse(config.deepSeekConversationsArchive.map { archive =>
      require(Files.isRegularFile(archive), s"DeepSeek conversations archive does not exist: $archive")
      archive -> DeepSeekDumper.dumpArchiveWithPrompts(archive, output, config.timezoneOffset)
    }).orElse(config.deepSeekConversationsFile.map { conversationsFile =>
      require(Files.isRegularFile(conversationsFile), s"DeepSeek conversations file does not exist: $conversationsFile")
      conversationsFile -> DeepSeekDumper.dumpWithPrompts(conversationsFile, output, config.timezoneOffset)
    })
    deepSeekExport.foreach { case (source, result) =>
      combinedPrompts ++= result.prompts
      println(s"Exported ${result.newPromptCount} new DeepSeek prompt(s) from $source; " +
        s"${result.promptCount} total in ${output.resolve(DeepSeekDumper.DeepSeekDirectoryName)}")
      exportedAny = true
    }
    if (exportedAny) {
      CombinedDumper.write(output, combinedPrompts, config.timezoneOffset)
      println(s"Exported ${combinedPrompts.size} combined prompt(s) to ${output.resolve("all-prompts.txt")}")
    } else println("No configured sources; nothing to export.")
  }
}

final case class Session(
    id: String,
    prompts: Vector[Prompt],
    workingDirectory: Option[String]
)

final case class Prompt(text: String, timestamp: Option[String])

object Dumper {
  val CodexDirectoryName = "codex"
  private val StateFileName = ".prompts-state.json"

  def dump(input: Path, output: Path): Int =
    dumpWithPrompts(input, output, TimestampFormatter.MoscowOffset).sessionCount

  def dumpWithPrompts(input: Path, output: Path, timezoneOffset: ZoneOffset): CodexExport = {
    val codexOutput = output.resolve(CodexDirectoryName)
    Files.createDirectories(codexOutput)
    val files = Using.resource(Files.walk(input))(_.iterator.asScala.filter(Files.isRegularFile(_)).toVector)
    val codexFiles = files.filter(_.getFileName.toString.endsWith(".jsonl"))

    val sessions = codexFiles.flatMap(CodexParser.parse(_))
    val sourceSessions = sessions.filter { session =>
      if (session.workingDirectory.isEmpty) {
        Console.err.println(s"Skipping session ${session.id}: working directory is missing")
        false
      } else true
    }

    val sourcePrompts = sourceSessions.flatMap { session =>
      session.prompts.map { prompt =>
        CombinedPrompt("codex", session.id, session.workingDirectory, prompt.text, prompt.timestamp)
      }
    }
    val existingPrompts = loadExistingPrompts(codexOutput)
    val existingKeys = existingPrompts.iterator.map(deduplicationKey).toSet
    val newPrompts = sourcePrompts.distinctBy(deduplicationKey)
      .filterNot(prompt => existingKeys(deduplicationKey(prompt)))
    val prompts = (existingPrompts ++ newPrompts).distinctBy(deduplicationKey)
    writeState(codexOutput.resolve(StateFileName), prompts)
    val sessionsWithDirectory = sessionsFromPrompts(prompts)

    sessionsWithDirectory.groupBy(_.workingDirectory.get).foreach { case (workingDirectory, grouped) =>
      write(codexOutput, workingDirectory, grouped, timezoneOffset)
    }
    writeAll(codexOutput, sessionsWithDirectory, timezoneOffset)
    CodexExport(sessionsWithDirectory.size, newPrompts.size, prompts.size, prompts)
  }

  private def write(
      output: Path, workingDirectory: String, sessions: Vector[Session], timezoneOffset: ZoneOffset): Unit = {
    val relativeDirectory = relativeWorkingDirectory(workingDirectory)
    val directory = output.resolve(relativeDirectory).normalize
    require(directory.startsWith(output), s"Unsafe working directory: $workingDirectory")
    Files.createDirectories(directory)

    val orderedSessions = sessions.sortBy(session => session.prompts.flatMap(_.timestamp).minOption)
    val summary = orderedSessions.map(session => s"${session.id}: ${session.prompts.size} промтов").mkString("\n")
    val orderedPrompts = sessions.flatMap(session => session.prompts.map(session.id -> _)).sortBy {
      case (_, prompt) => prompt.timestamp.flatMap(parseTimestamp).getOrElse(Instant.MAX)
    }
    val listing = new StringBuilder
    orderedPrompts.foreach { case (sessionId, prompt) =>
      if (listing.nonEmpty) listing.append("\n\n")
      listing.append(s"Сессия: $sessionId\n")
      prompt.timestamp.foreach(value =>
        listing.append(s"Время запроса: ${TimestampFormatter.format(value, timezoneOffset)}\n"))
      listing.append(prompt.text)
    }

    val body = s"Сессии:\n$summary\n\nРабочая папка: $workingDirectory\n\nПромты:\n$listing"
    AtomicFileWriter.write(directory.resolve("prompts.txt"), body)
  }

  private def writeAll(output: Path, sessions: Vector[Session], timezoneOffset: ZoneOffset): Unit = {
    Files.createDirectories(output)
    val orderedSessions = sessions.sortBy(session => session.prompts.flatMap(_.timestamp).minOption)
    val summary = orderedSessions.map(session => s"${session.id}: ${session.prompts.size} промтов").mkString("\n")
    val orderedPrompts = sessions.flatMap { session =>
      session.prompts.map(prompt => (session.id, session.workingDirectory.get, prompt))
    }.sortBy { case (_, _, prompt) =>
      prompt.timestamp.flatMap(parseTimestamp).getOrElse(Instant.MAX)
    }
    val listing = new StringBuilder
    orderedPrompts.foreach { case (sessionId, workingDirectory, prompt) =>
      if (listing.nonEmpty) listing.append("\n\n")
      listing.append(s"Сессия: $sessionId\n")
      listing.append(s"Рабочая папка: $workingDirectory\n")
      prompt.timestamp.foreach(value =>
        listing.append(s"Время запроса: ${TimestampFormatter.format(value, timezoneOffset)}\n"))
      listing.append(prompt.text)
    }

    val body = s"Сессии:\n$summary\n\nПромты:\n$listing"
    AtomicFileWriter.write(output.resolve("all-prompts.txt"), body)
  }

  private def sessionsFromPrompts(prompts: Vector[CombinedPrompt]): Vector[Session] =
    prompts.groupBy(prompt => (prompt.workingDirectory.get, prompt.sessionId)).toVector.map {
      case ((workingDirectory, sessionId), sessionPrompts) =>
        Session(sessionId, sessionPrompts.map(prompt => Prompt(prompt.text, prompt.timestamp)), Some(workingDirectory))
    }

  private def loadExistingPrompts(codexOutput: Path): Vector[CombinedPrompt] = {
    val stateFile = codexOutput.resolve(StateFileName)
    if (Files.isRegularFile(stateFile)) readState(stateFile)
    else {
      val textFile = codexOutput.resolve("all-prompts.txt")
      if (Files.isRegularFile(textFile)) {
        val prompts = migrateTextExport(Files.readString(textFile, StandardCharsets.UTF_8))
        Console.err.println(s"Migrated ${prompts.size} existing Codex prompt(s) from $textFile")
        prompts
      } else Vector.empty
    }
  }

  private def readState(stateFile: Path): Vector[CombinedPrompt] = try {
    ujson.read(Files.readString(stateFile, StandardCharsets.UTF_8)).arr.toVector.map { value =>
      val obj = value.obj
      CombinedPrompt(
        "codex",
        string(obj.get("sessionId")).get,
        Some(string(obj.get("workingDirectory")).get),
        string(obj.get("text")).get,
        string(obj.get("timestamp"))
      )
    }
  } catch {
    case NonFatal(error) => throw new IllegalStateException(
      s"Cannot read Codex append state $stateFile; refusing to overwrite existing export: ${error.getMessage}", error)
  }

  private def writeState(stateFile: Path, prompts: Vector[CombinedPrompt]): Unit = {
    val json = ujson.Arr.from(prompts.map { prompt =>
      ujson.Obj(
        "sessionId" -> prompt.sessionId,
        "workingDirectory" -> prompt.workingDirectory.get,
        "timestamp" -> prompt.timestamp.map(ujson.Str(_)).getOrElse(ujson.Null),
        "text" -> prompt.text
      )
    }).render(indent = 2)
    AtomicFileWriter.write(stateFile, json)
  }

  private def migrateTextExport(text: String): Vector[CombinedPrompt] = {
    val listing = text.split("\\n\\nПромты:\\n", 2).lift(1).getOrElse("")
    val header = ("(?m)^Сессия: ([^\\r\\n]+)\\r?\\n" +
      "Рабочая папка: ([^\\r\\n]+)\\r?\\nВремя запроса: ([^\\r\\n]+)\\r?\\n").r
    val matches = header.findAllMatchIn(listing).toVector
    val prompts = matches.zipWithIndex.map { case (entry, index) =>
      val end = if (index + 1 < matches.size) matches(index + 1).start else listing.length
      val content = listing.substring(entry.end, end).stripSuffix("\n\n")
      CombinedPrompt("codex", entry.group(1), Some(entry.group(2)), content, Some(entry.group(3)))
    }.filter(_.text.nonEmpty)
    if (listing.contains("Сессия:") && prompts.isEmpty)
      throw new IllegalStateException("Cannot migrate the existing Codex text export; refusing to overwrite it")
    prompts
  }

  private def deduplicationKey(prompt: CombinedPrompt): (String, String, String) =
    (prompt.sessionId,
      prompt.timestamp.flatMap(TimestampFormatter.instant).map(_.toString).orElse(prompt.timestamp).getOrElse(""),
      prompt.text)

  private def string(value: Option[ujson.Value]): Option[String] =
    value.collect { case ujson.Str(text) => text }

  private def relativeWorkingDirectory(workingDirectory: String): Path = {
    val normalized = Paths.get(workingDirectory).normalize
    val components = normalized.iterator.asScala.map(_.toString).toVector
    require(!components.contains(".."), s"Unsafe working directory: $workingDirectory")
    components.foldLeft(Paths.get(""))(_.resolve(_))
  }

  private def parseTimestamp(value: String): Option[Instant] = Try(Instant.parse(value)).toOption
}

final case class CodexExport(
    sessionCount: Int,
    newPromptCount: Int,
    promptCount: Int,
    prompts: Vector[CombinedPrompt]
)

object CodexParser {
  def parse(file: Path): Option[Session] = Try {
    val values = Using.resource(Files.lines(file, StandardCharsets.UTF_8)) { lines =>
      lines.iterator.asScala.filter(_.trim.nonEmpty).map(line => ujson.read(line)).toVector
    }
    val id = values.collectFirst {
      case v if str(v, "type").contains("session_meta") => str(v("payload"), "id").get
    }.getOrElse(file.getFileName.toString.stripSuffix(".jsonl"))
    val isSubagent = values.collectFirst {
      case v if str(v, "type").contains("session_meta") =>
        v("payload").obj.get("source").exists {
          case source: ujson.Obj => source.value.contains("subagent")
          case _ => false
        }
    }.getOrElse(false)
    val workingDirectory = values.collectFirst {
      case v if str(v, "type").contains("session_meta") => str(v("payload"), "cwd")
    }.flatten
    val prompts = values.collect {
      case v if str(v, "type").contains("event_msg") && str(v("payload"), "type").contains("user_message") =>
        Prompt(str(v("payload"), "message").getOrElse(""), str(v, "timestamp"))
    }.filter(_.text.nonEmpty)
    Session(id, if (isSubagent) Vector.empty else prompts, workingDirectory)
  } match {
    case Success(session) if session.prompts.nonEmpty => Some(session)
    case Success(_) => None
    case Failure(error) =>
      Console.err.println(s"Skipping unreadable Codex session $file: ${error.getMessage}")
      None
  }

  private def str(value: ujson.Value, key: String): Option[String] =
    value.obj.get(key).collect { case ujson.Str(s) => s }
}
