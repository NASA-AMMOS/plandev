package gov.nasa.jpl.plandev.types;

public record MissionModelId(long id) {
  @Override
  public String toString() {
    return String.valueOf(this.id());
  }
}
