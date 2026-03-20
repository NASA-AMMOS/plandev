package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

public record ExternalDataset(
      int planId,
      int datasetId,
      Optional<Integer> simulationDatasetId,
      String startOffset,
      Map<String, List<ProfileSegment>> profiles
  ) {
  public static ExternalDataset fromJSON(ObjectNode json) {
    // Process Profile Map
    final var profileArray = json.get("dataset").get("profiles");
    final var profiles = new HashMap<String, List<ProfileSegment>>();
    for (final var entry : profileArray) {
      final ObjectNode e = (ObjectNode) entry;
      final String name = e.get("name").textValue();
      profiles.put(name, StreamSupport.stream(e.get("profile_segments").spliterator(), false).map(seg -> ProfileSegment.fromJSON((ObjectNode) seg)).toList());
    }

    return new ExternalDataset(
        json.get("plan_id").intValue(),
        json.get("dataset_id").intValue(),
        (json.get("simulation_dataset_id") == null || json.get("simulation_dataset_id").isNull()) ? Optional.empty() : Optional.of(json.get("simulation_dataset_id").intValue()),
        json.get("offset_from_plan_start").textValue(),
        profiles);
  }

  /**
   * Input definition of a Profile
   * @param name Name of the resource profile
   * @param type Type of the profile ("real" or "discrete")
   * @param schema Value Schema of the profile
   * @param segments Profile Segments that constitute this profile
   */
  public record ProfileInput(
      String name,
      String type,
      ValueSchema schema,
      List<ProfileSegmentInput> segments
  ) {

    /**
     * Input definition for a Profile segment
     * @param duration Duration of the segment in microseconds
     * @param dynamics Dynamics of the segment
     */
    public record ProfileSegmentInput(long duration, JsonNode dynamics) {
      public ObjectNode toJSON() {
        final var json = JsonNodeFactory.instance.objectNode()
            .put("duration", duration);
        return dynamics.isNull() ? json : json.set("dynamics", dynamics);
      }
    }

    public ObjectNode toJSON() {
      final var segmentsBuilder = JsonNodeFactory.instance.arrayNode();
      this.segments.forEach(s -> segmentsBuilder.add(s.toJSON()));

      return JsonNodeFactory.instance.objectNode()
          .put("type", type)
          .set("schema", schema.toJson())
          .set("segments", segmentsBuilder)
          ;
    }
  }
}
