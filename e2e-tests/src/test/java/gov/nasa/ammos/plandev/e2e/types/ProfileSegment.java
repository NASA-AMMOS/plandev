package gov.nasa.jpl.aerie.e2e.types;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;

public record ProfileSegment(String startOffset, boolean isGap, JsonValue dynamics) {
  public static ProfileSegment fromJSON(JsonObject json) {
    return new ProfileSegment(
        json.getString("start_offset"),
        json.getBoolean("is_gap"),
        json.get("dynamics")
    );
  }

  public JsonObject toJSON(final int datasetId, final int profileId) {
    return Json.createObjectBuilder()
               .add("dataset_id", datasetId)
               .add("profile_id", profileId)
               .add("start_offset", startOffset)
               .add("is_gap", isGap)
               .add("dynamics", dynamics)
               .build();
  }
}
