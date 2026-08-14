package gov.nasa.jpl.aerie.streamline_demo;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.DynamicsEffect;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export.Parameter;

import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.DynamicsMonad.effect;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Context.contextualized;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Context.inContext;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.set;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial.polynomial;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.spawn;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;

@ActivityType("CauseError")
public class CauseError {
  @Parameter
  public ResourceSelection selection;

  @Parameter
  public String effectName = "";

  @Parameter
  public String activityName = "";

  @EffectModel
  public void run(Mission mission) {
    inContext("CauseError" + (activityName.isEmpty() ? "" : " " + activityName), () -> {
      delay(HOUR);
      switch (selection) {
        case Bool -> causeError(mission.errorTestingModel.bool);
        case Counter -> causeError(mission.errorTestingModel.counter);
        case Continuous -> causeError(mission.errorTestingModel.continuous);
        case NonCommuting -> {
          spawn(contextualized("Branch 1", () -> {
            set(mission.errorTestingModel.counter, 5);
          }));
          spawn(contextualized("Branch 2", () -> {
            set(mission.errorTestingModel.counter, 6);
          }));
        }
        case DoomedClamp -> {
          MutableResource.set(mission.errorTestingModel.lowerBound, polynomial(-20, 0.001));
          MutableResource.set(mission.errorTestingModel.upperBound, polynomial(20, -0.001));
        }
      }
      delay(HOUR);
    });
  }

  private <D extends Dynamics<?, D>> void causeError(MutableResource<D> resource) {
    DynamicsEffect<D> effect = effect($ -> {
      throw new IllegalStateException("Pretend this is a more informative error message.");
    });
    if (effectName.isEmpty()) {
      resource.emit(effect);
    } else {
      resource.emit(effectName, effect);
    }
  }

  public enum ResourceSelection {
    Bool,
    Counter,
    Continuous,
    NonCommuting,
    DoomedClamp
  }
}
