package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import java.util.List;
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
      JsonObject value
  ) {
    public static Event fromJSON(JsonObject json){
      return new Event(
          json.getInt("topic_index"),
          json.getInt("transaction_index"),
          json.getString("causal_time"),
          json.getString("real_time"),
          json.getJsonObject("value")
      );
    }
  }

  public static Topic fromJSON(JsonObject json){
    final var schema = ValueSchema.fromJSON(json.getJsonObject("value_schema"));
    final var events = json.getJsonArray("events").getValuesAs(Event::fromJSON);
    return new Topic(
        json.getString("name"),
        schema,
        events
    );
  }

}
