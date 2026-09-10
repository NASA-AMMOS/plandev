package gov.nasa.ammos.plandev.merlin.server.models;

import java.nio.file.Path;
import java.util.Objects;

public final class MissionModelJar {
  public String name;
  public String version;
  public String mission;
  public String owner;

  /** How this model is backed: {@code "jar"} (a Java JAR uploaded to PlanDev) or {@code "declared"}
   *  (activity, resource and configuration types declared to PlanDev, with nothing behind them to run). */
  public String modelType;

  /** A digest of this model's declared type surface -- activity types with their parameters in
   *  declaration order, resource schemas, computed-attribute schemas, configuration parameters and
   *  capabilities. It answers "were these results produced against the model PlanDev has, or a drifted
   *  one?". Null for JAR models, whose bytes are stored and so cannot drift from their own metadata. */
  public String externalIdentityHash;

  /** The raw jsonb of what PlanDev may DO with this model -- see
   *  merlin.mission_model.external_capabilities. Carried as text because merlin does not interpret it;
   *  the client does, and an unsupported capability carries its own explanation for the client to show.
   *  Null for JAR models, whose capabilities are not in question. */
  public String externalCapabilities;

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
              && Objects.equals(this.externalIdentityHash, other.externalIdentityHash)
              && Objects.equals(this.externalCapabilities, other.externalCapabilities)
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
        externalIdentityHash,
        externalCapabilities,
        path
    );
  }
}
