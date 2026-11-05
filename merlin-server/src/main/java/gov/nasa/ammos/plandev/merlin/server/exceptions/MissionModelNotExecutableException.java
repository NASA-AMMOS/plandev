package gov.nasa.ammos.plandev.merlin.server.exceptions;

import gov.nasa.ammos.plandev.types.MissionModelId;

/**
 * Thrown instead of loading a non-executable mission model, which has no code: its definition file only declares
 * types. Unchecked, like DatabaseException, so it reaches the HTTP layer's handler from any caller that doesn't offer
 * something better.
 */
public final class MissionModelNotExecutableException extends RuntimeException {
  public final MissionModelId missionModelId;

  public MissionModelNotExecutableException(final MissionModelId missionModelId) {
    super("Mission model `%s` is non-executable, so it has no code to load.".formatted(missionModelId.id()));
    this.missionModelId = missionModelId;
  }
}
