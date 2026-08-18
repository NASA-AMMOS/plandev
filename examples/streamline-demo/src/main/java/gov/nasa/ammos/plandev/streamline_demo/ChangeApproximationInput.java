package gov.nasa.ammos.plandev.streamline_demo;

import gov.nasa.ammos.plandev.merlin.framework.annotations.ActivityType;
import gov.nasa.ammos.plandev.merlin.framework.annotations.Export;

import static gov.nasa.ammos.plandev.contrib.streamline.core.MutableResource.set;
import static gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.Polynomial.polynomial;

@ActivityType("ChangeApproximationInput")
public class ChangeApproximationInput {
  @Export.Parameter
  public double[] numeratorCoefficients;

  @Export.Parameter
  public double[] denominatorCoefficients;

  @ActivityType.EffectModel
  public void run(Mission mission) {
    set(mission.approximationModel.polynomial, polynomial(numeratorCoefficients));
    set(mission.approximationModel.divisor, polynomial(denominatorCoefficients));
  }
}
