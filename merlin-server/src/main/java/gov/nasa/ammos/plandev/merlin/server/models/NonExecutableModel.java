package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.json.JsonParser;
import gov.nasa.ammos.plandev.merlin.driver.MissionModelLoader;
import gov.nasa.ammos.plandev.merlin.protocol.model.InputType;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.tuple.Pair;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

  /*
    model: {
      type: 'object',
      additionalProperties: false,
      properties: {
        activity_types: {
          type: 'array',
          items: {
            $ref: '#/definitions/activity_type',
          },
        },
        resource_types: {
          type: 'array',
          items: {
            $ref: '#/definitions/resource_type',
          },
        },
        parameters: {
          type: 'array',
          items: {
            $ref: '#/definitions/model_parameter',
          },
        },
        metadata: {
          type: 'object',
        },
      },
      required: ['activity_types', 'resource_types'],
    },
   */

      /*
    model_parameter: {
      type: 'object',
      additionalProperties: false,
      properties: {
        name: {
          type: 'string',
          minLength: 1,
        },
        schema: {
          $ref: '#/definitions/value_schema',
        },
      },
      required: ['name', 'schema'],
    },

     */

  private final static JsonParser<InputType.Parameter> modelParamP = productP
      .field("name", stringP)
      .field("schema", valueSchemaP)
      .map(
          untuple(InputType.Parameter::new),
          param -> tuple(param.name(), param.schema()));







  private JsonObject loadModel() throws IOException {
    try(final var fileReader = new FileReader(definitionFile.toFile());
        final var jsonReader = Json.createReader(fileReader)) {
      return jsonReader.readObject();
    }
  }

  @Override
  public List<InputType.Parameter> extractModelParameters() throws IOException {
    final var model = loadModel();
    // "parameters" is an optional key on non-executable models
    if(!model.containsKey("parameters")) {
      return List.of();
    }
    final var jsonParams = model.getJsonArray("parameters");

    //jsonParams.

    return List.of();


  }

  @Override
  public Pair<Map<String, ActivityType>, List<String>> extractActivityTypesAndSubsystems()
  {
    return Pair.of(Map.of(), List.of());
  }

  @Override
  public Map<String, ValueSchema> extractResourceSchemas(final Instant planStart, final SerializedValue configuration)
  {
    return Map.of();
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
