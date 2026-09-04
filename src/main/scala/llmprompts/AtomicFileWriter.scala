package llmprompts

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption, StandardOpenOption}

object AtomicFileWriter {
  def write(target: Path, content: String): Unit = {
    val temporary = Files.createTempFile(target.getParent, ".llm-prompts-", ".tmp")
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
      try moveFile(temporary, target, atomic = true)
      catch { case _: AtomicMoveNotSupportedException => moveFile(temporary, target, atomic = false) }
    } finally Files.deleteIfExists(temporary)
  }

  private def moveFile(source: Path, target: Path, atomic: Boolean): Unit = {
    val options =
      if (atomic) Array(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      else Array(StandardCopyOption.REPLACE_EXISTING)
    Files.move(source, target, options: _*)
  }
}
