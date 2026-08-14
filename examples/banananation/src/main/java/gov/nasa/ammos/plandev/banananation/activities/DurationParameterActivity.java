package gov.nasa.ammos.plandev.banananation.activities;

import gov.nasa.ammos.plandev.banananation.Mission;
import gov.nasa.ammos.plandev.contrib.metadata.Unit;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.ammos.plandev.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;

import static gov.nasa.ammos.plandev.merlin.framework.ModelActions.delay;
import static gov.nasa.ammos.plandev.merlin.protocol.types.Duration.SECONDS;

/**
 * This activity type intentionally takes a duration as a parameter, but is not a ControllableDuration activity
 */
@ActivityType("DurationParameterActivity")
public record DurationParameterActivity(Duration duration) {

  @EffectModel
  public ComputedAttributes run(Mission mission) {
    delay(duration);
    return new ComputedAttributes(duration, duration.ratioOver(SECONDS));
  }

  @AutoValueMapper.Record
  public record ComputedAttributes(Duration duration, @Unit("s") Double durationInSeconds) {}
}
