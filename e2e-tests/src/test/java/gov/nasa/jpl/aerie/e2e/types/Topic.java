package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.StreamSupport;
public record Topic(
    String name,
    ValueSchema schema,
    List<Event> events
) {
  public record Event(
      int topicIndex,
      int transactionIndex,
      String causalTime,
      String realTime,
      ObjectNode value
  ) {
    public static Event fromJSON(ObjectNode json){
      return new Event(
          json.get("topic_index").intValue(),
          json.get("transaction_index").intValue(),
          json.get("causal_time").textValue(),
          json.get("real_time").textValue(),
          json.get("value")
      );
    }
  }

  public static Topic fromJSON(ObjectNode json){
    final var schema = ValueSchema.fromJSON(json.get("value_schema"));
    final var events = StreamSupport.stream(json.get("events").spliterator(), false).map(e -> Event.fromJSON((ObjectNode) e)).toList();
    return new Topic(
        json.get("name").textValue(),
        schema,
        events
    );
  }

}
