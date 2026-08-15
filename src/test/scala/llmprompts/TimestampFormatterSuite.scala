package llmprompts

import java.time.ZoneOffset

class TimestampFormatterSuite extends munit.FunSuite {
  test("converts timestamps to the configured fixed GMT offset") {
    assertEquals(
      TimestampFormatter.format("2026-08-14T08:30:00Z", ZoneOffset.ofHours(-5)),
      "2026-08-14T03:30:00-05:00"
    )
    assertEquals(
      TimestampFormatter.format("2026-08-14T12:00:00+03:00", ZoneOffset.ofHours(9)),
      "2026-08-14T18:00:00+09:00"
    )
  }
}
