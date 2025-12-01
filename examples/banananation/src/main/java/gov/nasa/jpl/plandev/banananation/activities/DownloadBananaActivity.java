package gov.nasa.jpl.plandev.banananation.activities;

import gov.nasa.jpl.plandev.banananation.Mission;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType.ParametricDuration;
import gov.nasa.jpl.plandev.merlin.framework.annotations.Export.Parameter;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;

import static gov.nasa.jpl.plandev.merlin.framework.ModelActions.delay;

/**
 * Internet technology has come a long way.
 *
 * @contact Jane Doe
 */
@ActivityType("DownloadBanana")
public final class DownloadBananaActivity {

  public enum ConnectionType {
    DSL,
    FiberOptic,
    DietaryFiberOptic
  }

  @Parameter
  public ConnectionType connection = ConnectionType.DSL;

  @ParametricDuration
  public Duration duration() {
    return switch (this.connection) {
      case DSL -> Duration.HOUR;
      case FiberOptic -> Duration.of(10, Duration.MINUTE);
      case DietaryFiberOptic -> Duration.MINUTE;
    };
  }

  @EffectModel
  public void run(final Mission mission) {
    delay(duration());
    mission.fruit.add(1);
  }
}
