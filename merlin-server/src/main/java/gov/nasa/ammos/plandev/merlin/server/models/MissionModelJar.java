package gov.nasa.ammos.plandev.merlin.server.models;

import java.nio.file.Path;

public sealed interface MissionModelJar permits ExecutableModel, NonExecutableModel {
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
}
