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

    val session = CodexParser.parse(file).get
    assertEquals(session.id, "session-1")
    assertEquals(session.prompts, Vector(
      Prompt("first prompt", Some("2026-08-02T06:17:17.230Z")),
      Prompt("second prompt", Some("2026-08-02T06:18:20.000Z")),
      Prompt("third prompt", Some("2026-08-02T06:18:30.000Z"))
    ))
    assertEquals(session.workingDirectory, Some("/work/project"))

    val output = Files.createTempDirectory("codex-output")
    assertEquals(Dumper.dump(root, output), 2)
    val text = Files.readString(output.resolve("work/project/prompts.txt"), StandardCharsets.UTF_8)
    assertEquals(text,
      "Сессии:\nsession-1: 3 промтов\nsession-2: 1 промтов\n\n" +
      "Рабочая папка: /work/project\n\nПромты:\n" +
      "Сессия: session-1\nВремя запроса: 2026-08-02T06:17:17.230Z\nfirst prompt\n\n" +
      "Сессия: session-2\nВремя запроса: 2026-08-02T06:18:00.000Z\nmiddle prompt\n\n" +
      "Сессия: session-1\nВремя запроса: 2026-08-02T06:18:20.000Z\nsecond prompt\n\n" +
      "Сессия: session-1\nВремя запроса: 2026-08-02T06:18:30.000Z\nthird prompt")
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

}
