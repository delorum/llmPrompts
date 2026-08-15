package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class AppConfigSuite extends munit.FunSuite {
  test("config path uses XDG_CONFIG_HOME with home config as fallback") {
    val home = Files.createTempDirectory("llm-prompts-home")
    val xdgConfigHome = Files.createTempDirectory("llm-prompts-xdg")

    assertEquals(
      AppConfig.configPath(Map(AppConfig.XdgConfigHomeEnvironment -> xdgConfigHome.toString), home),
      xdgConfigHome.resolve("llm-prompts/config.properties").toAbsolutePath.normalize
    )
    assertEquals(
      AppConfig.configPath(Map(AppConfig.XdgConfigHomeEnvironment -> "  "), home),
      home.resolve(".config/llm-prompts/config.properties").toAbsolutePath.normalize
    )
    assertEquals(
      AppConfig.configPath(Map.empty, home),
      home.resolve(".config/llm-prompts/config.properties").toAbsolutePath.normalize
    )
  }

  test("properties take precedence and environment supplies missing values") {
    val home = Files.createTempDirectory("llm-prompts-home")
    val configDir = Files.createDirectories(home.resolve(".config/llm-prompts"))
    val configFile = configDir.resolve("config.properties")
    Files.writeString(configFile,
      "codex.sessions.directory=~/.codex/sessions\n" +
      "deepseek.conversations.directory=~/downloads\n" +
      "deepseek.conversations.file=~/deepseek.json\n" +
      "deepseek.conversations.archive=~/deepseek.zip\n" +
      "timezone.offset.hours=-5\n", StandardCharsets.UTF_8)

    val config = AppConfig.load(configFile, Map(
      AppConfig.SessionsEnvironment -> "/ignored/sessions",
      AppConfig.DeepSeekConversationsDirectoryEnvironment -> "/ignored/downloads",
      AppConfig.DeepSeekConversationsEnvironment -> "/ignored/deepseek.json",
      AppConfig.DeepSeekConversationsArchiveEnvironment -> "/ignored/deepseek.zip",
      AppConfig.TimezoneOffsetHoursEnvironment -> "+4",
      AppConfig.OutputEnvironment -> "/exports/prompts"
    ), home)

    assertEquals(config.codexSessionsDirectory, Some(home.resolve(".codex/sessions").toAbsolutePath.normalize))
    assertEquals(config.deepSeekConversationsDirectory, Some(home.resolve("downloads").toAbsolutePath.normalize))
    assertEquals(config.deepSeekConversationsFile, Some(home.resolve("deepseek.json").toAbsolutePath.normalize))
    assertEquals(config.deepSeekConversationsArchive, Some(home.resolve("deepseek.zip").toAbsolutePath.normalize))
    assertEquals(config.outputDirectory.toString, "/exports/prompts")
    assertEquals(config.timezoneOffset.getTotalSeconds, -5 * 3600)
  }

  test("environment is used when the properties file does not exist") {
    val home = Files.createTempDirectory("llm-prompts-home")
    val config = AppConfig.load(home.resolve("missing.properties"), Map(
      AppConfig.SessionsEnvironment -> "~/sessions",
      AppConfig.DeepSeekConversationsDirectoryEnvironment -> "~/downloads",
      AppConfig.DeepSeekConversationsEnvironment -> "~/deepseek.json",
      AppConfig.DeepSeekConversationsArchiveEnvironment -> "~/deepseek.zip",
      AppConfig.TimezoneOffsetHoursEnvironment -> "+4",
      AppConfig.OutputEnvironment -> "~/exports"
    ), home)

    assertEquals(config.codexSessionsDirectory, Some(home.resolve("sessions").toAbsolutePath.normalize))
    assertEquals(config.deepSeekConversationsDirectory, Some(home.resolve("downloads").toAbsolutePath.normalize))
    assertEquals(config.deepSeekConversationsFile, Some(home.resolve("deepseek.json").toAbsolutePath.normalize))
    assertEquals(config.deepSeekConversationsArchive, Some(home.resolve("deepseek.zip").toAbsolutePath.normalize))
    assertEquals(config.outputDirectory, home.resolve("exports").toAbsolutePath.normalize)
    assertEquals(config.timezoneOffset.getTotalSeconds, 4 * 3600)
  }

  test("Codex and DeepSeek sources are optional") {
    val home = Files.createTempDirectory("llm-prompts-home")
    val config = AppConfig.load(home.resolve("missing.properties"), Map(
      AppConfig.OutputEnvironment -> "~/exports"
    ), home)

    assertEquals(config.codexSessionsDirectory, None)
    assertEquals(config.deepSeekConversationsDirectory, None)
    assertEquals(config.deepSeekConversationsFile, None)
    assertEquals(config.deepSeekConversationsArchive, None)
    assertEquals(config.timezoneOffset, TimestampFormatter.MoscowOffset)
  }

  test("timezone offset rejects values outside the supported range") {
    val home = Files.createTempDirectory("llm-prompts-home")
    val error = intercept[IllegalArgumentException] {
      AppConfig.load(home.resolve("missing.properties"), Map(
        AppConfig.OutputEnvironment -> "~/exports",
        AppConfig.TimezoneOffsetHoursEnvironment -> "19"
      ), home)
    }

    assert(error.getMessage.contains(AppConfig.TimezoneOffsetHoursProperty))
  }

  test("output directory remains required") {
    val home = Files.createTempDirectory("llm-prompts-home")
    val error = intercept[IllegalArgumentException] {
      AppConfig.load(home.resolve("missing.properties"), Map.empty, home)
    }

    assert(error.getMessage.contains(AppConfig.OutputProperty))
    assert(error.getMessage.contains(AppConfig.OutputEnvironment))
  }
}
