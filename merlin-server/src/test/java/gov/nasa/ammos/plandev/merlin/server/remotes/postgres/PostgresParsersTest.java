package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import javax.json.Json;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.pgTimestampP;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.nasa.jpl.aerie.types.Timestamp;
import org.junit.jupiter.api.Test;

public final class PostgresParsersTest {

  @Test
  public void testTimestampParser() {
    final var timestamp = "2022-01-01T23:43:59.83237+00:00";
    final var expected = Timestamp.fromString("2022-001T23:43:59.83237");
    final var actual = pgTimestampP.parse(Json.createValue(timestamp)).getSuccessOrThrow();

    assertEquals(expected, actual);
  }
}
