package llmprompts

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.util.Try

object TimestampFormatter {
  val MoscowOffset: ZoneOffset = ZoneOffset.ofHours(3)

  def instant(value: String): Option[Instant] = Try(Instant.parse(value)).toOption

  def format(value: String, offset: ZoneOffset): String =
    instant(value)
      .map(instant => DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.ofInstant(instant, offset)))
      .getOrElse(value)
}
