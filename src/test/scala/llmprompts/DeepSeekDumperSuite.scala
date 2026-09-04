package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.io.{ByteArrayOutputStream, PrintStream}
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
      "Сессия: conversation-2\nВремя запроса: 2026-08-14T11:30:00+03:00\nearlier\n\n" +
      "Сессия: conversation-2\nВремя запроса: 2026-08-14T11:30:00+03:00\nalso earlier\n\n" +
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

  test("loads matching archives from a directory and deduplicates repeated prompts") {
    val directory = Files.createTempDirectory("deepseek-archives")
    val repeated =
      """{"id":"conversation-1","mapping":{"1":{"message":{"inserted_at":"2026-08-14T08:30:00Z","fragments":[{"type":"REQUEST","content":"repeated"}]}}}}"""
    writeArchive(directory.resolve("deepseek_data-2026-08-14.zip"), s"[$repeated]")
    writeArchive(directory.resolve("deepseek_data-2026-08-15.zip"),
      s"[$repeated,{\"id\":\"conversation-2\",\"mapping\":{\"1\":{\"message\":{\"inserted_at\":\"2026-08-15T08:30:00Z\",\"fragments\":[{\"type\":\"REQUEST\",\"content\":\"new\"}]}}}}]")
    writeArchive(directory.resolve("unrelated.zip"), "not JSON")
    writeArchive(directory.resolve("deepseek_data-2026-08-16.zip"), "{}", "user.json")
    writeArchive(directory.resolve("deepseek_data-2026-08-17.zip"), "{}")
    writeArchive(directory.resolve("deepseek_data-2026-08-18.zip"),
      """[{"id":"conversation-3","mapping":{"1":{"message":{"inserted_at":"2026-08-16T08:30:00Z","fragments":[{"type":"REQUEST","content":"collected despite errors"}]}}}},{"mapping":{}},42]""")
    val output = Files.createTempDirectory("deepseek-directory-output")
    val errors = new ByteArrayOutputStream

    val result = Console.withErr(new PrintStream(errors, true, StandardCharsets.UTF_8)) {
      DeepSeekDumper.dumpDirectoryWithPrompts(directory, output, TimestampFormatter.MoscowOffset)
    }

    assertEquals(result.promptCount, 3)
    assertEquals(result.newPromptCount, 3)
    val text = Files.readString(output.resolve("deepseek/all-prompts.txt"), StandardCharsets.UTF_8)
    assertEquals("repeated".r.findAllIn(text).length, 1)
    assert(text.contains("new"))
    assert(text.contains("collected despite errors"))
    val errorText = errors.toString(StandardCharsets.UTF_8)
    assert(errorText.contains("deepseek_data-2026-08-16.zip"))
    assert(errorText.contains("conversations.json was not found"))
    assert(errorText.contains("deepseek_data-2026-08-17.zip"))
    assert(errorText.contains("unsupported conversations.json format"))
    assert(errorText.contains("deepseek_data-2026-08-18.zip"))
    assert(errorText.contains("conversation has no string id or mapping object"))
    assert(errorText.contains("conversation is not a JSON object"))
  }

  test("keeps previously exported prompts when older archives disappear") {
    val firstDirectory = Files.createTempDirectory("deepseek-first-archives")
    writeArchive(firstDirectory.resolve("deepseek_data-2026-08-14.zip"), conversationJson(
      "conversation-1", "2026-08-14T08:30:00Z", "old prompt"))
    val output = Files.createTempDirectory("deepseek-append-output")

    val first = DeepSeekDumper.dumpDirectoryWithPrompts(
      firstDirectory, output, TimestampFormatter.MoscowOffset)
    assertEquals((first.newPromptCount, first.promptCount), (1, 1))

    val secondDirectory = Files.createTempDirectory("deepseek-second-archives")
    writeArchive(secondDirectory.resolve("deepseek_data-2026-08-15.zip"), conversationJson(
      "conversation-2", "2026-08-15T08:30:00Z", "new prompt"))
    val second = DeepSeekDumper.dumpDirectoryWithPrompts(
      secondDirectory, output, TimestampFormatter.MoscowOffset)
    assertEquals((second.newPromptCount, second.promptCount), (1, 2))

    val third = DeepSeekDumper.dumpDirectoryWithPrompts(
      secondDirectory, output, TimestampFormatter.MoscowOffset)
    assertEquals((third.newPromptCount, third.promptCount), (0, 2))
    val text = Files.readString(output.resolve("deepseek/all-prompts.txt"), StandardCharsets.UTF_8)
    assertEquals("old prompt".r.findAllIn(text).length, 1)
    assertEquals("new prompt".r.findAllIn(text).length, 1)
    assert(Files.isRegularFile(output.resolve("deepseek/.prompts-state.json")))
  }

  test("migrates an existing text export before appending") {
    val output = Files.createTempDirectory("deepseek-migration-output")
    val deepSeekOutput = Files.createDirectories(output.resolve("deepseek"))
    Files.writeString(deepSeekOutput.resolve("all-prompts.txt"),
      "Сессии:\nold-session: 1 промтов\n\nПромты:\n" +
      "Сессия: old-session\nВремя запроса: 2026-08-14T11:30:00+03:00\nold migrated prompt",
      StandardCharsets.UTF_8)
    val input = Files.createTempFile("deepseek-new-conversations", ".json")
    Files.writeString(input, conversationJson("new-session", "2026-08-15T08:30:00Z", "new prompt"),
      StandardCharsets.UTF_8)

    val result = DeepSeekDumper.dumpWithPrompts(input, output, TimestampFormatter.MoscowOffset)

    assertEquals((result.newPromptCount, result.promptCount), (1, 2))
    val text = Files.readString(deepSeekOutput.resolve("all-prompts.txt"), StandardCharsets.UTF_8)
    assert(text.contains("old migrated prompt"))
    assert(text.contains("new prompt"))
  }

  private def conversationJson(conversationId: String, timestamp: String, content: String): String =
    s"""[{"id":"$conversationId","mapping":{"1":{"message":{"inserted_at":"$timestamp","fragments":[{"type":"REQUEST","content":"$content"}]}}}}]"""

  private def writeArchive(
      path: java.nio.file.Path, json: String, entryName: String = "conversations.json"): Unit = {
    val zip = new ZipOutputStream(Files.newOutputStream(path))
    try {
      zip.putNextEntry(new ZipEntry(entryName))
      zip.write(json.getBytes(StandardCharsets.UTF_8))
      zip.closeEntry()
    } finally zip.close()
  }
}
