package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;

@ActivityType("BasicFooActivity")
public final class BasicFooActivity {
  @Parameter
  public Duration duration = Duration.of(2, Duration.SECONDS);

  @ActivityType.EffectModel
  @ActivityType.ControllableDuration(parameterName = "duration")
  public void run(final Mission mission) {
    delay(duration);
    mission.activitiesExecuted.add(1);
  }
}
