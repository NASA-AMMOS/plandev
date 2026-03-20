package gov.nasa.jpl.aerie.merlin.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

public record SimulationFailure(
    String type,
    String message,
    JsonNode data,
    String trace,
    Instant timestamp
) {
  public static final class Builder {
    private String type = "";
    private String message = "";
    private String trace = "";
    private JsonNode data = JsonNodeFactory.instance.objectNode();

    public Builder type(final String type) {
      this.type = type;
      return this;
    }

    public Builder message(final String message) {
      this.message = message;
      return this;
    }

    public Builder trace(final Throwable throwable) {
      final var sw = new StringWriter();
      throwable.printStackTrace(new PrintWriter(sw));
      this.trace = sw.toString();
      return this;
    }

    public Builder data(final JsonNode data) {
      this.data = data;
      return this;
    }

    public SimulationFailure build() {
      return new SimulationFailure(type, message, data, trace, Instant.now());
    }
  }
}
