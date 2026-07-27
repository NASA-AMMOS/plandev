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

  /** For external models: the name of the trusted backend (from EXTERNAL_MODEL_BACKENDS config) that
   *  hosts this model. Merlin resolves it to a URL. Null for JAR models. */
  public String externalBackend;

  /** For external models: the key selecting which model to use on {@link #externalBackend} (a backend may
   *  host several). Null for JAR models. */
  public String externalModelKey;

  /** For external models: the identityHash the backend reported at the last introspection -- a digest of
   *  its declared activity types, parameters, and resource schemas. Compared against the backend's
   *  current hash before simulating, so a redeployed backend with a different type surface is detected
   *  rather than silently simulated against stale stored types. Null for JAR models, and for external
   *  models registered before it was recorded. */
  public String externalIdentityHash;

  /** For external models: the raw jsonb of what the backend says PlanDev may DO with this model --
   *  see merlin.mission_model.external_capabilities. Carried as text because merlin does not
   *  interpret it; the client does. Null for JAR models, and for external models registered before
   *  it was recorded. */
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
              && Objects.equals(this.externalBackend, other.externalBackend)
              && Objects.equals(this.externalModelKey, other.externalModelKey)
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
        externalBackend,
        externalModelKey,
        externalIdentityHash,
        externalCapabilities,
        path
    );
  }
}
