package gov.nasa.jpl.plandev.streamline_demo;

import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.plandev.merlin.framework.annotations.ActivityType.EffectModel;
import gov.nasa.jpl.plandev.merlin.framework.annotations.Export.Parameter;

import static gov.nasa.jpl.plandev.contrib.streamline.core.MutableResource.set;
import static gov.nasa.jpl.plandev.contrib.streamline.modeling.polynomial.Polynomial.polynomial;

@ActivityType("ChangeDesiredRate")
public class ChangeDesiredRate {
  @Parameter
  public Bucket bucket;

  @Parameter
  public double rate;

  @EffectModel
  public void run(Mission mission) {
    var rateToChange = switch (bucket) {
      case A -> mission.dataModel.desiredRateA;
      case B -> mission.dataModel.desiredRateB;
      case C -> mission.dataModel.desiredRateC;
    };
    set(rateToChange, polynomial(rate));
  }

  public enum Bucket { A, B, C }
}
