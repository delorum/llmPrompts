package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.ZoneOffset
import java.util.Properties
import scala.jdk.CollectionConverters._
import scala.util.Using

final case class AppConfig(
    codexSessionsDirectory: Option[Path],
    deepSeekConversationsDirectory: Option[Path],
    deepSeekConversationsFile: Option[Path],
    deepSeekConversationsArchive: Option[Path],
    outputDirectory: Path,
    timezoneOffset: ZoneOffset
)

object AppConfig {
  val SessionsProperty = "codex.sessions.directory"
  val OutputProperty = "output.directory"
  val DeepSeekConversationsProperty = "deepseek.conversations.file"
  val DeepSeekConversationsArchiveProperty = "deepseek.conversations.archive"
  val DeepSeekConversationsDirectoryProperty = "deepseek.conversations.directory"
  val TimezoneOffsetHoursProperty = "timezone.offset.hours"
  val SessionsEnvironment = "LLM_PROMPTS_CODEX_SESSIONS_DIRECTORY"
  val OutputEnvironment = "LLM_PROMPTS_OUTPUT_DIRECTORY"
  val DeepSeekConversationsEnvironment = "LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_FILE"
  val DeepSeekConversationsArchiveEnvironment = "LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_ARCHIVE"
  val DeepSeekConversationsDirectoryEnvironment = "LLM_PROMPTS_DEEPSEEK_CONVERSATIONS_DIRECTORY"
  val TimezoneOffsetHoursEnvironment = "LLM_PROMPTS_TIMEZONE_OFFSET_HOURS"
  val XdgConfigHomeEnvironment = "XDG_CONFIG_HOME"

  def load(): AppConfig = {
    val home = Paths.get(System.getProperty("user.home")).toAbsolutePath.normalize
    val environment = sys.env
    load(configPath(environment, home), environment, home)
  }

  private[llmprompts] def configPath(environment: Map[String, String], home: Path): Path = {
    val configHome = environment.get(XdgConfigHomeEnvironment)
      .map(_.trim).filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(home.resolve(".config"))
    configHome.resolve("llm-prompts/config.properties").toAbsolutePath.normalize
  }

  private[llmprompts] def load(configFile: Path, environment: Map[String, String], home: Path): AppConfig = {
    val properties = readProperties(configFile)
    val sessions = optionalSetting(properties, SessionsProperty, environment, SessionsEnvironment)
    val output = setting(properties, OutputProperty, environment, OutputEnvironment)
    val deepSeek = optionalSetting(
      properties, DeepSeekConversationsProperty, environment, DeepSeekConversationsEnvironment)
    val deepSeekArchive = optionalSetting(
      properties, DeepSeekConversationsArchiveProperty, environment, DeepSeekConversationsArchiveEnvironment)
    val deepSeekDirectory = optionalSetting(
      properties, DeepSeekConversationsDirectoryProperty, environment, DeepSeekConversationsDirectoryEnvironment)
    val timezoneOffset = optionalSetting(
      properties, TimezoneOffsetHoursProperty, environment, TimezoneOffsetHoursEnvironment)
      .map(parseTimezoneOffset).getOrElse(ZoneOffset.ofHours(3))
    AppConfig(
      sessions.map(resolvePath(_, home)),
      deepSeekDirectory.map(resolvePath(_, home)),
      deepSeek.map(resolvePath(_, home)),
      deepSeekArchive.map(resolvePath(_, home)),
      resolvePath(output, home),
      timezoneOffset
    )
  }

  private def readProperties(configFile: Path): Map[String, String] = {
    if (!Files.exists(configFile)) Map.empty
    else {
      require(Files.isRegularFile(configFile), s"Configuration path is not a file: $configFile")
      val properties = new Properties
      Using.resource(Files.newBufferedReader(configFile, StandardCharsets.UTF_8))(properties.load)
      properties.stringPropertyNames.asScala.map(key => key -> properties.getProperty(key)).toMap
    }
  }

  private def setting(
      properties: Map[String, String],
      propertyName: String,
      environment: Map[String, String],
      environmentName: String
  ): String =
    properties.get(propertyName).map(_.trim).filter(_.nonEmpty)
      .orElse(environment.get(environmentName).map(_.trim).filter(_.nonEmpty))
      .getOrElse(throw new IllegalArgumentException(
        s"Missing configuration: set '$propertyName' in config.properties or '$environmentName' in the environment"))

  private def optionalSetting(
      properties: Map[String, String],
      propertyName: String,
      environment: Map[String, String],
      environmentName: String
  ): Option[String] =
    properties.get(propertyName).map(_.trim).filter(_.nonEmpty)
      .orElse(environment.get(environmentName).map(_.trim).filter(_.nonEmpty))

  private def resolvePath(value: String, home: Path): Path = {
    val expanded =
      if (value == "~") home
      else if (value.startsWith("~/")) home.resolve(value.drop(2))
      else Paths.get(value)
    expanded.toAbsolutePath.normalize
  }

  private def parseTimezoneOffset(value: String): ZoneOffset =
    try ZoneOffset.ofHours(value.toInt)
    catch {
      case _: NumberFormatException => throw new IllegalArgumentException(
        s"'$TimezoneOffsetHoursProperty' must be an integer number of hours from -18 to +18: $value")
      case _: java.time.DateTimeException => throw new IllegalArgumentException(
        s"'$TimezoneOffsetHoursProperty' must be an integer number of hours from -18 to +18: $value")
    }
}
