package gov.nasa.ammos.plandev.scheduler.server.exceptions;

import gov.nasa.ammos.plandev.types.MissionModelId;

public class NoSuchMissionModelException extends Exception {
  private final MissionModelId id;

  public NoSuchMissionModelException(final MissionModelId id) {
    super("No mission model exists with id `" + id + "`");
    this.id = id;
  }

  public MissionModelId getInvalidMissionModelId() {
    return this.id;
  }
}
