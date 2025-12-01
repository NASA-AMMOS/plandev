package gov.nasa.jpl.plandev.banananation.activities;

import gov.nasa.jpl.plandev.banananation.Mission;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.plandev.merlin.framework.annotations.Export.Parameter;

import static gov.nasa.jpl.plandev.banananation.generated.ActivityActions.spawn;
import static gov.nasa.jpl.plandev.banananation.generated.ActivityActions.call;
import static gov.nasa.jpl.plandev.merlin.framework.ModelActions.*;
import static gov.nasa.jpl.plandev.merlin.protocol.types.Duration.SECOND;

public final class DecomposingSpawnActivity {
  @ActivityType("DecomposingSpawnParent")
  public static final class DecomposingSpawnParentActivity {
    @Parameter
    public String label = "unlabeled";

    @EffectModel
    public void run(final Mission mission) {
      spawn(mission, new DecomposingSpawnChildActivity(1));
      delay(1, SECOND);
      spawn(mission, new DecomposingSpawnChildActivity(2));
    }
  }

  @ActivityType("DecomposingSpawnChild")
  public static final class DecomposingSpawnChildActivity {
    @Parameter
    public int counter = 0;

    public DecomposingSpawnChildActivity() {}

    public DecomposingSpawnChildActivity(final int counter) {
      this.counter = counter;
    }

    @EffectModel
    public void run(final Mission mission) {
      delay(2, SECOND);
    }
  }
}
