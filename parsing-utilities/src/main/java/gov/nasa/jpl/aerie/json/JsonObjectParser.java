package gov.nasa.jpl.aerie.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.function.Function;

public interface JsonObjectParser<T> extends JsonParser<T> {
  @Override
  ObjectNode unparse(final T value);

  @Override
  default <S> JsonObjectParser<S> map(final Convert<T, S> transform) {
    Objects.requireNonNull(transform);

    final var self = this;

    return new JsonObjectParser<>() {
      @Override
      public ObjectNode getSchema(final SchemaCache anchors) {
        return self.getSchema(anchors);
      }

      @Override
      public JsonParseResult<S> parse(final JsonNode json) {
        return self.parse(json).mapSuccess(transform::from);
      }

      @Override
      public ObjectNode unparse(final S value) {
        return self.unparse(transform.to(value));
      }
    };
  }

  @Override
  default <S> JsonObjectParser<S> map(final Function<T, S> from, final Function<S, T> to) {
    return this.map(Convert.between(from, to));
  }
}
