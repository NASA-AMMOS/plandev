package gov.nasa.jpl.aerie.e2e.types;

import javax.json.JsonObject;
import java.util.List;

public record SchedulingDSLTypesResponse(String status, String reason, List<TypescriptFile> typescriptFiles) {
  public static SchedulingDSLTypesResponse fromJSON(JsonObject json){
    final var files = json.getJsonArray("typescriptFiles")
                          .getValuesAs(TypescriptFile::fromJSON);
    return new SchedulingDSLTypesResponse(json.getString("status"), json.getString("reason", null), files);
  }
}
