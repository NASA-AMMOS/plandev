package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.json.JsonParser;
import gov.nasa.ammos.plandev.merlin.protocol.model.InputType;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.http.InvalidJsonEntityException;
import org.apache.commons.lang3.tuple.Pair;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static gov.nasa.ammos.plandev.json.BasicParsers.listP;
import static gov.nasa.ammos.plandev.json.BasicParsers.objP;
import static gov.nasa.ammos.plandev.json.BasicParsers.productP;
import static gov.nasa.ammos.plandev.json.BasicParsers.stringP;
import static gov.nasa.ammos.plandev.json.Uncurry.tuple;
import static gov.nasa.ammos.plandev.json.Uncurry.untuple;
import static gov.nasa.ammos.plandev.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;

public record NonExecutableModel(
    String name,
    String version,
    String mission,
    String owner,
    Path definitionFile
) implements MissionModelFile {

  private record ParsedMissionModel(
      Map<String, ActivityType> activityTypes,
      Map<String, ValueSchema> resourceTypes,
      Optional<List<InputType.Parameter>> parameters,
      Optional<JsonObject> metadata
  ){
    public ParsedMissionModel(
        List<ActivityType> activityTypes,
        List<Pair<String, ValueSchema>> resourceTypes,
        Optional<List<InputType.Parameter>> parameters,
        Optional<JsonObject> metadata
    ) {
      this(
          activityTypes.stream().collect(Collectors.toMap(ActivityType::name, t -> t)),
          resourceTypes.stream().collect(Collectors.toMap(Pair::getKey, Pair::getValue)),
          parameters,
          metadata.map(JsonValue::asJsonObject)
      );
    }

    /**
     * Generate the list of Subsystems in the model from the Activity Types
     */
    public List<String> getSubsystems() {
      // HashSet is used to avoid duplicate entries
      final var subsystems = new HashSet<String>();

      for(final var type : activityTypes.values()) {
        type.subsystem().ifPresent(subsystems::add);
      }

      return subsystems.stream().toList();
    }
  }

  private final static JsonParser<InputType.Parameter> modelParamP = productP
      .field("name", stringP)
      .field("schema", valueSchemaP)
      .map(
          untuple(InputType.Parameter::new),
          param -> tuple(param.name(), param.schema()));

  private final static JsonParser<ActivityType> activityTypeP = productP
      .field("name", stringP)
      .optionalField("parameters", listP(modelParamP))
      .optionalField("required_parameters", listP(stringP))
      .optionalField("computed_attributes_schema", valueSchemaP)
      .optionalField("description", stringP)
      .optionalField("subsystem", stringP)
      .map(
          untuple((
              name,
              params,
              reqParams,
              compAttr,
              desc,
              subsys) -> new ActivityType(
                  name,
                  params.orElse(List.of()),
                  reqParams.orElse(List.of()),
                  compAttr.orElse(ValueSchema.ofStruct(Map.of())), // Default Value
                  desc,
                  subsys
          )),
          actType -> tuple(
              actType.name(),
              Optional.of(actType.parameters()),
              Optional.of(actType.requiredParameters()),
              Optional.of(actType.computedAttributesValueSchema()),
              actType.description(),
              actType.subsystem()
          ));

  private final static JsonParser<Pair<String, ValueSchema>> resourceTypeP = productP
      .field("name", stringP)
      .field("schema", valueSchemaP);

  private final static JsonParser<ParsedMissionModel> modelP = productP
      .field("activity_types", listP(activityTypeP))
      .field("resource_types", listP(resourceTypeP))
      .optionalField("parameters", listP(modelParamP))
      .optionalField("metadata", objP)
      .map(
          untuple(ParsedMissionModel::new),
          model -> tuple(
              model.activityTypes.values().stream().toList(),
              model.resourceTypes.entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue())).toList(),
              model.parameters,
              model.metadata
          )
      );

  private ParsedMissionModel loadModel() throws IOException, InvalidJsonEntityException {
    try(final var fileReader = new FileReader(definitionFile.toFile());
        final var jsonReader = Json.createReader(fileReader)) {
      return modelP
          .parse(jsonReader.readObject())
          .getSuccessOrThrow($ -> new InvalidJsonEntityException(List.of($)));
    }
  }

  @Override
  public List<InputType.Parameter> extractModelParameters() throws IOException, InvalidJsonEntityException {
    final var model = loadModel();
    return model.parameters.orElse(List.of());
  }

  @Override
  public Pair<Map<String, ActivityType>, List<String>> extractActivityTypesAndSubsystems()
  throws IOException, InvalidJsonEntityException {
    final var model = loadModel();
    return Pair.of(model.activityTypes(), model.getSubsystems());
  }

  @Override
  public Map<String, ValueSchema> extractResourceSchemas(final Instant planStart, final SerializedValue configuration)
  throws InvalidJsonEntityException, IOException
  {
    final var model = loadModel();
    return model.resourceTypes();
  }

  @Override
  public boolean equals(final Object object) {
    if (object.getClass() != NonExecutableModel.class) {
      return false;
    }

    final NonExecutableModel other = (NonExecutableModel) object;
    return
        (Objects.equals(this.name(), other.name())
         && Objects.equals(this.version, other.version)
         && Objects.equals(this.mission, other.mission)
         && Objects.equals(this.owner, other.owner)
         && Objects.equals(this.definitionFile, other.definitionFile)
        );
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name,
        version,
        mission,
        owner,
        definitionFile
    );
  }
}
