package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
public record ProfileSegment(String startOffset, boolean isGap, JsonNode dynamics) {
  public static ProfileSegment fromJSON(ObjectNode json) {
    return new ProfileSegment(
        json.get("start_offset").textValue(),
        json.get("is_gap").booleanValue(),
        json.get("dynamics")
    );
  }

  public ObjectNode toJSON(final int datasetId, final int profileId) {
    return JsonNodeFactory.instance.objectNode()
               .put("dataset_id", datasetId)
               .put("profile_id", profileId)
               .put("start_offset", startOffset)
               .put("is_gap", isGap)
               .set("dynamics", dynamics)
               ;
  }
}
