package gov.nasa.jpl.aerie.merlin.server.models;

import java.nio.file.Path;
import java.util.Objects;

public final class MissionModelJar {
  public String name;
  public String version;
  public String mission;
  public String owner;

  /**
   * The path to the Mission Model JAR
   *
   * File at this location should not
   * be deleted except by its owner
   */
  public Path path;

  public MissionModelJar() {}

  @Override
  public boolean equals(final Object object) {
      if (object.getClass() != MissionModelJar.class) {
          return false;
      }

      final MissionModelJar other = (MissionModelJar)object;
      return
              (  Objects.equals(this.name, other.name)
              && Objects.equals(this.version, other.version)
              && Objects.equals(this.mission, other.mission)
              && Objects.equals(this.owner, other.owner)
              && Objects.equals(this.path, other.path)
              );
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name,
        version,
        mission,
        owner,
        path
    );
  }
}
