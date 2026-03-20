package gov.nasa.jpl.aerie.e2e.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.StreamSupport;

public record SchedulingDSLTypesResponse(String status, String reason, List<TypescriptFile> typescriptFiles) {
  public static SchedulingDSLTypesResponse fromJSON(ObjectNode json){
    final var files = json.get("typescriptFiles")
StreamSupport.stream(                          .spliterator(), false).map(e -> TypescriptFile.fromJSON((ObjectNode) e)).toList();
    return new SchedulingDSLTypesResponse(json.get("status").textValue(), (json.has("reason") && !json.get("reason").isNull() ? json.get("reason").textValue() : null), files);
  }
}
