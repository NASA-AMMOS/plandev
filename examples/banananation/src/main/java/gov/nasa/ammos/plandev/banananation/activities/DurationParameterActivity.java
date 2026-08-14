package gov.nasa.jpl.aerie.banananation.activities;

import gov.nasa.jpl.aerie.banananation.Mission;
import gov.nasa.jpl.aerie.contrib.metadata.Unit;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

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
