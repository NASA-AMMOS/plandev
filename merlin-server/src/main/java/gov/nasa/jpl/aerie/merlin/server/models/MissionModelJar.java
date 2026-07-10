package gov.nasa.jpl.aerie.merlin.server.models;

import java.nio.file.Path;
import java.util.Objects;

public final class MissionModelJar {
  public String name;
  public String version;
  public String mission;
  public String owner;

  /** Discriminator for how this model is backed: "jar" (Java JAR) or "external" (foreign backend). */
  public String modelType;

  /** For external models: the HTTP endpoint of the backend that simulates this model. Null for JAR models. */
  public String externalBackendUrl;

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
              && Objects.equals(this.modelType, other.modelType)
              && Objects.equals(this.externalBackendUrl, other.externalBackendUrl)
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
        modelType,
        externalBackendUrl,
        path
    );
  }
}
