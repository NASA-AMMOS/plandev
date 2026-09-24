package gov.nasa.ammos.plandev.merlin.server.models;

import java.nio.file.Path;
import java.util.Objects;

public record ExecutableModel(
    String name,
    String version,
    String mission,
    String owner,
    Path definitionFile
) implements MissionModelFile {
  @Override
  public boolean equals(final Object object) {
    if (object.getClass() != ExecutableModel.class) {
      return false;
    }

    final ExecutableModel other = (ExecutableModel) object;
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
