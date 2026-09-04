package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ParserSuite extends munit.FunSuite {
  test("Codex parser exports event user messages once and ignores context response items") {
    val root = Files.createTempDirectory("codex-parser")
    val dir = Files.createDirectories(root.resolve("2026/08/02"))
    val file = dir.resolve("rollout.jsonl")
    Files.writeString(file,
      """{"type":"session_meta","payload":{"id":"session-1","cwd":"/work/project"}}
        |{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"<environment_context>secret</environment_context>"}]}}
        |{"timestamp":"2026-08-02T06:17:17.230Z","type":"event_msg","payload":{"type":"user_message","message":"first prompt"}}
        |{"timestamp":"2026-08-02T06:18:20.000Z","type":"event_msg","payload":{"type":"user_message","message":"second prompt"}}
        |{"timestamp":"2026-08-02T06:18:30.000Z","type":"event_msg","payload":{"type":"user_message","message":"third prompt"}}
        |""".stripMargin,
      StandardCharsets.UTF_8)

    val secondFile = dir.resolve("rollout-2.jsonl")
    Files.writeString(secondFile,
      """{"type":"session_meta","payload":{"id":"session-2","cwd":"/work/project"}}
        |{"timestamp":"2026-08-02T06:18:00.000Z","type":"event_msg","payload":{"type":"user_message","message":"middle prompt"}}
        |""".stripMargin, StandardCharsets.UTF_8)

    val otherDir = Files.createDirectories(root.resolve("2026/08/03"))
    Files.writeString(otherDir.resolve("rollout-3.jsonl"),
      """{"type":"session_meta","payload":{"id":"session-3","cwd":"/other/project"}}
        |{"timestamp":"2026-08-02T06:17:30.000Z","type":"event_msg","payload":{"type":"user_message","message":"other prompt"}}
        |""".stripMargin, StandardCharsets.UTF_8)

    val session = CodexParser.parse(file).get
    assertEquals(session.id, "session-1")
    assertEquals(session.prompts, Vector(
      Prompt("first prompt", Some("2026-08-02T06:17:17.230Z")),
      Prompt("second prompt", Some("2026-08-02T06:18:20.000Z")),
      Prompt("third prompt", Some("2026-08-02T06:18:30.000Z"))
    ))
    assertEquals(session.workingDirectory, Some("/work/project"))

    val output = Files.createTempDirectory("codex-output")
    assertEquals(Dumper.dump(root, output), 3)
    val text = Files.readString(output.resolve("codex/work/project/prompts.txt"), StandardCharsets.UTF_8)
    assertEquals(text,
      "Сессии:\nsession-1: 3 промтов\nsession-2: 1 промтов\n\n" +
      "Рабочая папка: /work/project\n\nПромты:\n" +
      "Сессия: session-1\nВремя запроса: 2026-08-02T09:17:17.23+03:00\nfirst prompt\n\n" +
      "Сессия: session-2\nВремя запроса: 2026-08-02T09:18:00+03:00\nmiddle prompt\n\n" +
      "Сессия: session-1\nВремя запроса: 2026-08-02T09:18:20+03:00\nsecond prompt\n\n" +
      "Сессия: session-1\nВремя запроса: 2026-08-02T09:18:30+03:00\nthird prompt")

    val allText = Files.readString(output.resolve("codex/all-prompts.txt"), StandardCharsets.UTF_8)
    assertEquals(allText,
      "Сессии:\nsession-1: 3 промтов\nsession-3: 1 промтов\nsession-2: 1 промтов\n\nПромты:\n" +
      "Сессия: session-1\nРабочая папка: /work/project\nВремя запроса: 2026-08-02T09:17:17.23+03:00\nfirst prompt\n\n" +
      "Сессия: session-3\nРабочая папка: /other/project\nВремя запроса: 2026-08-02T09:17:30+03:00\nother prompt\n\n" +
      "Сессия: session-2\nРабочая папка: /work/project\nВремя запроса: 2026-08-02T09:18:00+03:00\nmiddle prompt\n\n" +
      "Сессия: session-1\nРабочая папка: /work/project\nВремя запроса: 2026-08-02T09:18:20+03:00\nsecond prompt\n\n" +
      "Сессия: session-1\nРабочая папка: /work/project\nВремя запроса: 2026-08-02T09:18:30+03:00\nthird prompt")
  }

  test("Codex parser ignores internal subagent sessions") {
    val root = Files.createTempDirectory("codex-subagent")
    val file = root.resolve("rollout.jsonl")
    Files.writeString(file,
      """{"type":"session_meta","payload":{"id":"internal","source":{"subagent":{"other":"guardian"}}}}
        |{"type":"event_msg","payload":{"type":"user_message","message":"synthetic request"}}
        |""".stripMargin, StandardCharsets.UTF_8)

    assertEquals(CodexParser.parse(file), None)
  }

  test("Codex export keeps prompts after their source sessions disappear") {
    val firstInput = Files.createTempDirectory("codex-first-input")
    writeSession(firstInput, "old-session", "/work/project", "2026-08-14T08:30:00Z", "old prompt")
    val output = Files.createTempDirectory("codex-append-output")

    val first = Dumper.dumpWithPrompts(firstInput, output, TimestampFormatter.MoscowOffset)
    assertEquals((first.newPromptCount, first.promptCount, first.sessionCount), (1, 1, 1))

    val secondInput = Files.createTempDirectory("codex-second-input")
    writeSession(secondInput, "new-session", "/work/project", "2026-08-15T08:30:00Z", "new prompt")
    val second = Dumper.dumpWithPrompts(secondInput, output, TimestampFormatter.MoscowOffset)
    assertEquals((second.newPromptCount, second.promptCount, second.sessionCount), (1, 2, 2))

    val third = Dumper.dumpWithPrompts(secondInput, output, TimestampFormatter.MoscowOffset)
    assertEquals((third.newPromptCount, third.promptCount, third.sessionCount), (0, 2, 2))
    val text = Files.readString(output.resolve("codex/all-prompts.txt"), StandardCharsets.UTF_8)
    assertEquals("old prompt".r.findAllIn(text).length, 1)
    assertEquals("new prompt".r.findAllIn(text).length, 1)
    assert(Files.isRegularFile(output.resolve("codex/.prompts-state.json")))
  }

  test("Codex export migrates an existing aggregate text file before appending") {
    val output = Files.createTempDirectory("codex-migration-output")
    val codexOutput = Files.createDirectories(output.resolve("codex"))
    Files.writeString(codexOutput.resolve("all-prompts.txt"),
      "Сессии:\nold-session: 1 промтов\n\nПромты:\n" +
      "Сессия: old-session\nРабочая папка: /old/project\n" +
      "Время запроса: 2026-08-14T11:30:00+03:00\nold migrated prompt",
      StandardCharsets.UTF_8)
    val input = Files.createTempDirectory("codex-migration-input")
    writeSession(input, "new-session", "/new/project", "2026-08-15T08:30:00Z", "new prompt")

    val result = Dumper.dumpWithPrompts(input, output, TimestampFormatter.MoscowOffset)

    assertEquals((result.newPromptCount, result.promptCount, result.sessionCount), (1, 2, 2))
    val text = Files.readString(codexOutput.resolve("all-prompts.txt"), StandardCharsets.UTF_8)
    assert(text.contains("old migrated prompt"))
    assert(text.contains("new prompt"))
  }

  private def writeSession(
      root: java.nio.file.Path,
      sessionId: String,
      workingDirectory: String,
      timestamp: String,
      prompt: String
  ): Unit = {
    val content =
      ujson.Obj("type" -> "session_meta", "payload" -> ujson.Obj(
        "id" -> sessionId, "cwd" -> workingDirectory)).render() + "\n" +
      ujson.Obj("timestamp" -> timestamp, "type" -> "event_msg", "payload" -> ujson.Obj(
        "type" -> "user_message", "message" -> prompt)).render() + "\n"
    Files.writeString(root.resolve(s"$sessionId.jsonl"), content, StandardCharsets.UTF_8)
  }

}
