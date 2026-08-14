package gov.nasa.jpl.aerie.json;

import org.junit.jupiter.api.Test;

import javax.json.Json;

import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ProductParsersTest {
  @Test
  public void restWithOneField() {
    final var parser = ProductParsers.productP
        .field("x", stringP)
        .rest();

    final var str = parser.parse(Json.createObjectBuilder().add("x", "foo").add("y", 1).build());

    assertEquals(JsonParseResult.success("foo"), str);
  }

  @Test
  public void restWithNoFields() {
    final var parser = ProductParsers.productP.rest();

    final var unit = parser.parse(Json.createObjectBuilder().add("x", "foo").add("y", 1).build());

    assertEquals(JsonParseResult.success(Unit.UNIT), unit);
  }
}
