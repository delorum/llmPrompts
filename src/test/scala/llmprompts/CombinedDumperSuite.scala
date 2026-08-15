package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class CombinedDumperSuite extends munit.FunSuite {
  test("combines Codex and DeepSeek prompts in chronological order with LLM metadata") {
    val output = Files.createTempDirectory("combined-output")
    CombinedDumper.write(output, Vector(
      CombinedPrompt("codex", "codex-session", Some("/work/project"), "codex prompt",
        Some("2026-08-14T12:00:00+03:00")),
      CombinedPrompt("deepseek", "deepseek-session", None, "deepseek prompt",
        Some("2026-08-14T08:30:00Z"))
    ))

    val text = Files.readString(output.resolve("all-prompts.txt"), StandardCharsets.UTF_8)
    assertEquals(text,
      "Промты:\n" +
      "LLM: deepseek\nСессия: deepseek-session\nВремя запроса: 2026-08-14T11:30:00+03:00\ndeepseek prompt\n\n" +
      "LLM: codex\nСессия: codex-session\nРабочая папка: /work/project\n" +
      "Время запроса: 2026-08-14T12:00:00+03:00\ncodex prompt")
  }
}
