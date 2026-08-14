package gov.nasa.jpl.aerie.e2e.types;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ExternalDataset(
      int planId,
      int datasetId,
      Optional<Integer> simulationDatasetId,
      String startOffset,
      Map<String, List<ProfileSegment>> profiles
  ) {
  public static ExternalDataset fromJSON(JsonObject json) {
    // Process Profile Map
    final var profileArray = json.getJsonObject("dataset").getJsonArray("profiles");
    final var profiles = new HashMap<String, List<ProfileSegment>>();
    for (final var entry : profileArray) {
      final JsonObject e = entry.asJsonObject();
      final String name = e.getString("name");
      profiles.put(name, e.getJsonArray("profile_segments").getValuesAs(ProfileSegment::fromJSON));
    }

    return new ExternalDataset(
        json.getInt("plan_id"),
        json.getInt("dataset_id"),
        json.isNull("simulation_dataset_id") ? Optional.empty() : Optional.of(json.getInt("simulation_dataset_id")),
        json.getString("offset_from_plan_start"),
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
    public record ProfileSegmentInput(long duration, JsonValue dynamics) {
      public JsonObject toJSON() {
        final var json = Json.createObjectBuilder()
            .add("duration", duration);
        return dynamics.equals(JsonValue.NULL) ? json.build() : json.add("dynamics", dynamics).build();
      }
    }

    public JsonObject toJSON() {
      final var segmentsBuilder = Json.createArrayBuilder();
      this.segments.forEach(s -> segmentsBuilder.add(s.toJSON()));

      return Json.createObjectBuilder()
          .add("type", type)
          .add("schema", schema.toJson())
          .add("segments", segmentsBuilder)
          .build();
    }
  }
}
