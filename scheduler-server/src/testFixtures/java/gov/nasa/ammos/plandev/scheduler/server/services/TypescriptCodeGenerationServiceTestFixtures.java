package gov.nasa.jpl.aerie.scheduler.server.services;

import java.util.List;
import java.util.Map;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.server.models.ActivityType;
import gov.nasa.jpl.aerie.scheduler.server.models.ResourceType;

public final class TypescriptCodeGenerationServiceTestFixtures {

  public static final MerlinDatabaseService.MissionModelTypes MISSION_MODEL_TYPES =
      new MerlinDatabaseService.MissionModelTypes(
          List.of(
              new ActivityType(
                  "SampleActivity1",
                  Map.of(
                      "variant",
                      ValueSchema.ofVariant(List.of(
                          new ValueSchema.Variant(
                              "option1", "option1"
                          ),
                          new ValueSchema.Variant(
                              "option2",
                              "option2")
                      )),
                      "duration",
                      ValueSchema.DURATION,
                      "fancy",
                      ValueSchema.ofStruct(Map.of(
                          "subfield1",
                          ValueSchema.STRING,
                          "subfield2",
                          ValueSchema.ofSeries(ValueSchema.ofStruct(Map.of("subsubfield1", ValueSchema.REAL)))
                      ))
                  ),
                  Map.of()
              ),
              new ActivityType(
                  "SampleActivity2",
                  Map.of(
                      "quantity",
                      ValueSchema.REAL
                  ),
                  Map.of(
                      "my preset",
                      Map.of("quantity", SerializedValue.of(5))
                  )
              ),
              new ActivityType(
                  "SampleActivity3",
                  Map.of(
                      "variant",
                      ValueSchema.ofVariant(List.of(
                        new ValueSchema.Variant("option1", "option1"),
                        new ValueSchema.Variant("option2", "option2")
                      ))
                  ),
                  Map.of(
                      "my preset",
                      Map.of("variant", SerializedValue.of("option1"))
                  )
              ),
              new ActivityType(
                  "SampleActivityEmpty",
                  Map.of(),
                  Map.of()
              )
          ),
          List.of(
              new ResourceType("/sample/resource/1", ValueSchema.REAL),
              new ResourceType("/sample/resource/3", ValueSchema.ofVariant(List.of(
                  new ValueSchema.Variant(
                      "option1", "option1"
                  ),
                  new ValueSchema.Variant(
                      "option2", "option2"
                  )
              ))),
              new ResourceType("/sample/resource/2", ValueSchema.ofStruct(
                  Map.of(
                      "field1", ValueSchema.BOOLEAN,
                      "field2", ValueSchema.ofVariant(List.of(
                          new ValueSchema.Variant("ABC", "ABC"),
                          new ValueSchema.Variant("DEF", "DEF")
                      ))
                  )
              ))
          )
      );
}
