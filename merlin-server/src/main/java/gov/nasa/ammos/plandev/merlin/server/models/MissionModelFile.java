package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.merlin.driver.MissionModelLoader;
import gov.nasa.ammos.plandev.merlin.protocol.model.InputType;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.server.http.InvalidJsonEntityException;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface MissionModelFile permits ExecutableModel, NonExecutableModel {
  String name();
  String version();
  String mission();
  String owner();
  /**
   * The path to the file (JAR or JSON) that defines this Mission Model.
   *
   * File at this location should not
   * be deleted except by its owner
   */
  Path definitionFile();

  List<InputType.Parameter> extractModelParameters() throws MissionModelLoader.MissionModelLoadException, IOException,
                                                            InvalidJsonEntityException;
  Pair<Map<String, ActivityType>, List<String>> extractActivityTypesAndSubsystems()
  throws MissionModelLoader.MissionModelLoadException, IOException, InvalidJsonEntityException;
  Map<String, ValueSchema> extractResourceSchemas(Instant planStart, SerializedValue configuration)
  throws MissionModelLoader.MissionModelLoadException, InvalidJsonEntityException, IOException;
}
