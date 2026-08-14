package gov.nasa.jpl.aerie.streamline_demo;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.*;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.linear.Linear;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.approximate;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Approximation.relative;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.DifferentiableResources.asDifferentiable;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.IntervalFunctions.byBoundingError;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.IntervalFunctions.byUniformSampling;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.ErrorEstimates.errorByOptimization;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.ErrorEstimates.errorByQuadraticApproximation;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.SecantApproximation.secantApproximation;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.UnstructuredResources.approximateAsLinear;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.UnstructuredResources.approximateAsLinear;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.UnstructuredResources.asUnstructured;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.monads.UnstructuredResourceApplicative.map;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.*;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.approximateAsLinear;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.*;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MINUTE;

public class ApproximationModel {
  private static final double EPSILON = 1e-10;

  public MutableResource<Polynomial> polynomial;
  public MutableResource<Polynomial> divisor;

  public Resource<Linear> assumedLinear;
  public Resource<Linear> uniformApproximation;
  public Resource<Linear> differentiableApproximation;
  public Resource<Linear> directApproximation;
  public Resource<Linear> defaultApproximation;

  public Resource<Unstructured<Double>> rationalFunction;
  public Resource<Linear> uniformApproximation2;
  public Resource<Linear> directApproximation2;
  public Resource<Linear> defaultApproximation2;

  public Resource<Unstructured<Double>> trigFunction;
  public Resource<Linear> trigDefaultApproximation;

  public ApproximationModel(final Registrar registrar, final Configuration config) {
    final double tolerance = config.approximationTolerance;

    polynomial = polynomialResource(1);
    divisor = polynomialResource(1);

    assumedLinear = assumeLinear(polynomial);
    defaultApproximation = approximateAsLinear(polynomial, tolerance);
    uniformApproximation = approximate(
        polynomial,
            SecantApproximation.<Polynomial>secantApproximation(byUniformSampling(MINUTE)));
    differentiableApproximation = approximate(
        asDifferentiable(polynomial),
        secantApproximation(byBoundingError(
            tolerance,
            SECOND,
            HOUR.times(24),
            relative(errorByQuadraticApproximation(), EPSILON))));
    directApproximation = approximate(
        polynomial,
        secantApproximation(byBoundingError(
            tolerance,
            SECOND,
            HOUR.times(24),
            Approximation.<Polynomial>relative(errorByOptimization(), EPSILON))));

    rationalFunction = map(
            asUnstructured(polynomial), asUnstructured(divisor), (p, q) -> p / q);

    defaultApproximation2 = approximateAsLinear(rationalFunction, tolerance);
    uniformApproximation2 = approximate(rationalFunction,
            SecantApproximation.<Unstructured<Double>>secantApproximation(byUniformSampling(MINUTE)));
    directApproximation2 = approximate(rationalFunction,
            secantApproximation(byBoundingError(
                    tolerance,
                    SECOND,
                    HOUR.times(24),
                    Approximation.<Unstructured<Double>>relative(errorByOptimization(), EPSILON))));

    trigFunction = map(asUnstructured(polynomial), asUnstructured(divisor),
            (p, q) -> Math.sin(p * Math.exp(-q / Math.PI)));
    trigDefaultApproximation = approximateAsLinear(trigFunction, tolerance);

    registrar.real("approximation/polynomial/assumedLinear", assumedLinear);
    registrar.real("approximation/polynomial/default", defaultApproximation);
    registrar.real("approximation/polynomial/uniform", uniformApproximation);
    registrar.real("approximation/polynomial/differentiable", differentiableApproximation);
    registrar.real("approximation/polynomial/direct", directApproximation);

    registrar.real("approximation/rational/default", defaultApproximation2);
    registrar.real("approximation/rational/uniform", uniformApproximation2);
    registrar.real("approximation/rational/direct", directApproximation2);

    registrar.real("approximation/trig/default", trigDefaultApproximation);
  }
}
