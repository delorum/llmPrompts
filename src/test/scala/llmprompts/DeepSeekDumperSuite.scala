package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}

class DeepSeekDumperSuite extends munit.FunSuite {
  test("exports only REQUEST fragments from all conversations in chronological order") {
    val input = Files.createTempFile("deepseek-conversations", ".json")
    Files.writeString(input,
      """[
        |  {"id":"conversation-1","mapping":{
        |    "1":{"message":{"inserted_at":"2026-08-14T12:00:00+03:00","fragments":[{"type":"REQUEST","content":"later"}]}},
        |    "2":{"message":{"inserted_at":"2026-08-14T12:01:00+03:00","fragments":[{"type":"RESPONSE","content":"not exported"}]}}
        |  }},
        |  {"id":"conversation-2","mapping":{
        |    "1":{"message":{"inserted_at":"2026-08-14T08:30:00Z","fragments":[{"type":"REQUEST","content":"earlier"},{"type":"REQUEST","content":"also earlier"}]}}
        |  }}
        |]""".stripMargin, StandardCharsets.UTF_8)
    val output = Files.createTempDirectory("deepseek-output")

    assertEquals(DeepSeekDumper.dump(input, output), 3)
    val text = Files.readString(output.resolve("deepseek/all-prompts.txt"), StandardCharsets.UTF_8)
    assertEquals(text,
      "Сессии:\nconversation-2: 2 промтов\nconversation-1: 1 промтов\n\nПромты:\n" +
      "Сессия: conversation-2\nВремя запроса: 2026-08-14T08:30:00Z\nearlier\n\n" +
      "Сессия: conversation-2\nВремя запроса: 2026-08-14T08:30:00Z\nalso earlier\n\n" +
      "Сессия: conversation-1\nВремя запроса: 2026-08-14T12:00:00+03:00\nlater")
  }

  test("reads conversations.json directly from a DeepSeek ZIP archive") {
    val archive = Files.createTempFile("deepseek-conversations", ".zip")
    val json =
      """[{"id":"archived-conversation","mapping":{"1":{"message":{"inserted_at":"2026-08-14T08:30:00Z","fragments":[{"type":"REQUEST","content":"from archive"}]}}}}]"""
    val zip = new ZipOutputStream(Files.newOutputStream(archive))
    try {
      zip.putNextEntry(new ZipEntry("conversations.json"))
      zip.write(json.getBytes(StandardCharsets.UTF_8))
      zip.closeEntry()
    } finally zip.close()
    val output = Files.createTempDirectory("deepseek-archive-output")

    assertEquals(DeepSeekDumper.dumpArchive(archive, output), 1)
    val text = Files.readString(output.resolve("deepseek/all-prompts.txt"), StandardCharsets.UTF_8)
    assert(text.contains("Сессия: archived-conversation"))
    assert(text.contains("from archive"))
  }
}
