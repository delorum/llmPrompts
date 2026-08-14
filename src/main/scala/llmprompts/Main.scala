package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.Instant
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try, Using}

object Main {
  def main(args: Array[String]): Unit = {
    if (args.nonEmpty) {
      Console.err.println("Usage: llm-prompt-dumper")
      sys.exit(2)
    }

    val config = AppConfig.load()
    val output = config.outputDirectory
    var exportedAny = false
    config.codexSessionsDirectory.foreach { input =>
      require(Files.isDirectory(input), s"Codex sessions directory does not exist: $input")
      require(input != output && !input.startsWith(output), "Input and output directories must not overlap")
      val dumped = Dumper.dump(input, output)
      println(s"Exported $dumped Codex session(s) from $input to ${output.resolve(Dumper.CodexDirectoryName)}")
      exportedAny = true
    }
    val deepSeekExport = config.deepSeekConversationsArchive.map { archive =>
      require(Files.isRegularFile(archive), s"DeepSeek conversations archive does not exist: $archive")
      archive -> DeepSeekDumper.dumpArchive(archive, output)
    }.orElse(config.deepSeekConversationsFile.map { conversationsFile =>
      require(Files.isRegularFile(conversationsFile), s"DeepSeek conversations file does not exist: $conversationsFile")
      conversationsFile -> DeepSeekDumper.dump(conversationsFile, output)
    })
    deepSeekExport.foreach { case (source, prompts) =>
      println(s"Exported $prompts DeepSeek prompt(s) from $source to ${output.resolve(DeepSeekDumper.DeepSeekDirectoryName)}")
      exportedAny = true
    }
    if (!exportedAny) println("No configured sources; nothing to export.")
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

  def dump(input: Path, output: Path): Int = {
    val codexOutput = output.resolve(CodexDirectoryName)
    val files = Using.resource(Files.walk(input))(_.iterator.asScala.filter(Files.isRegularFile(_)).toVector)
    val codexFiles = files.filter(_.getFileName.toString.endsWith(".jsonl"))

    val sessions = codexFiles.flatMap(CodexParser.parse(_))
    val sessionsWithDirectory = sessions.filter { session =>
      if (session.workingDirectory.isEmpty) {
        Console.err.println(s"Skipping session ${session.id}: working directory is missing")
        false
      } else true
    }

    sessionsWithDirectory.groupBy(_.workingDirectory.get).foreach { case (workingDirectory, grouped) =>
      write(codexOutput, workingDirectory, grouped)
    }
    writeAll(codexOutput, sessionsWithDirectory)
    sessionsWithDirectory.size
  }

  private def write(output: Path, workingDirectory: String, sessions: Vector[Session]): Unit = {
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
      prompt.timestamp.foreach(value => listing.append(s"Время запроса: $value\n"))
      listing.append(prompt.text)
    }

    val body = s"Сессии:\n$summary\n\nРабочая папка: $workingDirectory\n\nПромты:\n$listing"
    Files.writeString(directory.resolve("prompts.txt"), body, StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
  }

  private def writeAll(output: Path, sessions: Vector[Session]): Unit = {
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
      prompt.timestamp.foreach(value => listing.append(s"Время запроса: $value\n"))
      listing.append(prompt.text)
    }

    val body = s"Сессии:\n$summary\n\nПромты:\n$listing"
    Files.writeString(output.resolve("all-prompts.txt"), body, StandardCharsets.UTF_8,
      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
  }

  private def relativeWorkingDirectory(workingDirectory: String): Path = {
    val normalized = Paths.get(workingDirectory).normalize
    val components = normalized.iterator.asScala.map(_.toString).toVector
    require(!components.contains(".."), s"Unsafe working directory: $workingDirectory")
    components.foldLeft(Paths.get(""))(_.resolve(_))
  }

  private def parseTimestamp(value: String): Option[Instant] = Try(Instant.parse(value)).toOption
}

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
