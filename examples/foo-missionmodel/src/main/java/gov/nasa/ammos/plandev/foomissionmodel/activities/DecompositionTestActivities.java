package gov.nasa.jpl.aerie.foomissionmodel.activities;

import gov.nasa.jpl.aerie.foomissionmodel.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;

import static gov.nasa.jpl.aerie.foomissionmodel.generated.ActivityActions.call;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

public final class DecompositionTestActivities {
  @ActivityType("parent")
  public static final class ParentActivity {
    @Parameter
    public String label = "unlabeled";

    @EffectModel
    public void run(final Mission mission) {
      call(mission, new ChildActivity(1));
      delay(1, SECOND);
      call(mission, new ChildActivity(2));
    }
  }

  @ActivityType("child")
  public static final class ChildActivity {
    @Parameter
    public int counter = 0;

    public ChildActivity() {}

    public ChildActivity(final int counter) {
      this.counter = counter;
    }

    @EffectModel
    public void run(final Mission mission) {
      delay(2, SECONDS);
    }
  }
}
