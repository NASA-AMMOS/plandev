package gov.nasa.ammos.plandev.streamline_demo;

import gov.nasa.ammos.plandev.contrib.streamline.core.MutableResource;
import gov.nasa.ammos.plandev.contrib.streamline.core.Resource;
import gov.nasa.ammos.plandev.contrib.streamline.modeling.Registrar;
import gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.LinearBoundaryConsistencySolver;
import gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.LinearBoundaryConsistencySolver.Domain;
import gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.Polynomial;
import gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.PolynomialResources;

import static gov.nasa.ammos.plandev.contrib.streamline.core.Resources.eraseExpiry;
import static gov.nasa.ammos.plandev.contrib.streamline.core.Resources.forward;
import static gov.nasa.ammos.plandev.contrib.streamline.core.monads.ResourceMonad.*;
import static gov.nasa.ammos.plandev.contrib.streamline.modeling.discrete.DiscreteResources.choose;
import static gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.LinearBoundaryConsistencySolver.Comparison.*;
import static gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.LinearBoundaryConsistencySolver.LinearExpression.lx;
import static gov.nasa.ammos.plandev.contrib.streamline.modeling.polynomial.PolynomialResources.*;

public class DataModel {
  public MutableResource<Polynomial> desiredRateA = PolynomialResources.polynomialResource(0);
  public MutableResource<Polynomial> desiredRateB = PolynomialResources.polynomialResource(0);
  public MutableResource<Polynomial> desiredRateC = PolynomialResources.polynomialResource(0);
  public MutableResource<Polynomial> upperBoundOnTotalVolume = PolynomialResources.polynomialResource(10);

  public Resource<Polynomial> actualRateA, actualRateB, actualRateC;
  public MutableResource<Polynomial> volumeA, volumeB, volumeC;
  public Resource<Polynomial> totalVolume;

  public DataModel(final Registrar registrar, final Configuration config) {
    // Adding a "derivative" operation makes the constraint problem no longer solvable without backtracking.
    // Instead of solving for it directly, we'll solve in two steps, one for rate, one for volume.

    // Set up the rate solver
    var rateSolver = new LinearBoundaryConsistencySolver("DataModel Rate Solver");
    var rateA = rateSolver.variable("rateA", Domain::upperBound);
    var rateB = rateSolver.variable("rateB", Domain::upperBound);
    var rateC = rateSolver.variable("rateC", Domain::upperBound);
    this.actualRateA = rateA.resource();
    this.actualRateB = rateB.resource();
    this.actualRateC = rateC.resource();

    // Use a simple feedback loop on volumes to do the integration and clamping.
    this.volumeA = PolynomialResources.polynomialResource(0);
    this.volumeB = PolynomialResources.polynomialResource(0);
    this.volumeC = PolynomialResources.polynomialResource(0);
    var clampedVolumeA = clamp(this.volumeA, constant(0), upperBoundOnTotalVolume);
    var volumeB_ub = subtract(upperBoundOnTotalVolume, clampedVolumeA);
    var clampedVolumeB = clamp(this.volumeB, constant(0), volumeB_ub);
    var volumeC_ub = subtract(volumeB_ub, clampedVolumeB);
    var clampedVolumeC = clamp(this.volumeC, constant(0), volumeC_ub);
    var correctedVolumeA = map(clampedVolumeA, actualRateA, (v, r) -> r.integral(v.extract()));
    var correctedVolumeB = map(clampedVolumeB, actualRateB, (v, r) -> r.integral(v.extract()));
    var correctedVolumeC = map(clampedVolumeC, actualRateC, (v, r) -> r.integral(v.extract()));
    // Use the corrected integral values to set volumes, but erase expiry information in the process to avoid loops:
    forward(eraseExpiry(correctedVolumeA), this.volumeA);
    forward(eraseExpiry(correctedVolumeB), this.volumeB);
    forward(eraseExpiry(correctedVolumeC), this.volumeC);

    // Integrate the actual rates.
    totalVolume = add(this.volumeA, this.volumeB, this.volumeC);

    // Then use the solver to adjust the actual rates

    // When full, we never write more than the upper bound will tolerate, in total
    var isFull = greaterThanOrEquals(totalVolume, upperBoundOnTotalVolume);
    var totalRate_ub = choose(isFull, differentiate(upperBoundOnTotalVolume), constant(Double.POSITIVE_INFINITY));
    rateSolver.declare(lx(rateA).add(lx(rateB)).add(lx(rateC)), LessThanOrEquals, lx(totalRate_ub));

    // We only exceed the desired rate when we try to delete from an empty bucket.
    var isEmptyA = lessThanOrEquals(this.volumeA, 0);
    var isEmptyB = lessThanOrEquals(this.volumeB, 0);
    var isEmptyC = lessThanOrEquals(this.volumeC, 0);
    var rateA_ub = choose(isEmptyA, max(desiredRateA, constant(0)), desiredRateA);
    var rateB_ub = choose(isEmptyB, max(desiredRateB, constant(0)), desiredRateB);
    var rateC_ub = choose(isEmptyC, max(desiredRateC, constant(0)), desiredRateC);
    rateSolver.declare(lx(rateA), LessThanOrEquals, lx(rateA_ub));
    rateSolver.declare(lx(rateB), LessThanOrEquals, lx(rateB_ub));
    rateSolver.declare(lx(rateC), LessThanOrEquals, lx(rateC_ub));

    // We cannot delete from an empty bucket
    var rateA_lb = choose(isEmptyA, constant(0), constant(Double.NEGATIVE_INFINITY));
    var rateB_lb = choose(isEmptyB, constant(0), constant(Double.NEGATIVE_INFINITY));
    var rateC_lb = choose(isEmptyC, constant(0), constant(Double.NEGATIVE_INFINITY));
    rateSolver.declare(lx(rateA), GreaterThanOrEquals, lx(rateA_lb));
    rateSolver.declare(lx(rateB), GreaterThanOrEquals, lx(rateB_lb));
    rateSolver.declare(lx(rateC), GreaterThanOrEquals, lx(rateC_lb));

    registerStates(registrar, config);
  }

  private void registerStates(Registrar registrar, Configuration config) {
    registrar.real("desiredRateA", assumeLinear(desiredRateA), "The desiredRateA description");
    registrar.real("desiredRateB", assumeLinear(desiredRateB), "The desiredRateB description");
    registrar.real("desiredRateC", assumeLinear(desiredRateC), "The desiredRateC description");

    registrar.real("actualRateA", assumeLinear(actualRateA), "The actualRateA description");
    registrar.real("actualRateB", assumeLinear(actualRateB), "The actualRateB description");
    registrar.real("actualRateC", assumeLinear(actualRateC), "The actualRateC description");

    registrar.real("volumeA", assumeLinear(volumeA));
    registrar.real("volumeB", assumeLinear(volumeB));
    registrar.real("volumeC", assumeLinear(volumeC));
    registrar.real("totalVolume", assumeLinear(totalVolume));
    registrar.real("maxVolume", assumeLinear(upperBoundOnTotalVolume));
  }
}
