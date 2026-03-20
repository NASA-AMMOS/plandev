package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static gov.nasa.jpl.aerie.json.BasicParsers.stringP;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ProductParsersTest {
  @Test
  public void restWithOneField() {
    final var parser = ProductParsers.productP
        .field("x", stringP)
        .rest();

    final var obj = JsonNodeFactory.instance.objectNode();
    obj.put("x", "foo");
    obj.put("y", 1);
    final var str = parser.parse(obj);

    assertEquals(JsonParseResult.success("foo"), str);
  }

  @Test
  public void restWithNoFields() {
    final var parser = ProductParsers.productP.rest();

    final var obj = JsonNodeFactory.instance.objectNode();
    obj.put("x", "foo");
    obj.put("y", 1);
    final var unit = parser.parse(obj);

    assertEquals(JsonParseResult.success(Unit.UNIT), unit);
  }
}
