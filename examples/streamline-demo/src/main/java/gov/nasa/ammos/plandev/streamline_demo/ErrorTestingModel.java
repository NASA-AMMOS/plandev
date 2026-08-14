package gov.nasa.jpl.aerie.streamline_demo;

import gov.nasa.jpl.aerie.contrib.serialization.mappers.BooleanValueMapper;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.IntegerValueMapper;
import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.Registrar;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.Discrete;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteResources;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.monads.DiscreteResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialResources.*;

public class ErrorTestingModel {
  public MutableResource<Discrete<Boolean>> bool = DiscreteResources.discreteResource(true);
  public MutableResource<Discrete<Integer>> counter = DiscreteResources.discreteResource(5);
  public MutableResource<Polynomial> continuous = PolynomialResources.polynomialResource(1);
  public Resource<Polynomial> derived = multiply(
      continuous,
      asPolynomial(map(counter, c -> (double) c)),
      asPolynomial(map(bool, $ -> $ ? 1.0 : -1.0)));

  public MutableResource<Polynomial> upperBound = name(PolynomialResources.polynomialResource(5), "upperBound");
  public MutableResource<Polynomial> lowerBound = PolynomialResources.polynomialResource(-5);
  public Resource<Polynomial> clamped = clamp(constant(10), lowerBound, upperBound);

  public ErrorTestingModel(final Registrar registrar, final Configuration config) {
    registrar.discrete("errorTesting/bool", bool, new BooleanValueMapper());
    registrar.discrete("errorTesting/counter", counter, new IntegerValueMapper());
    registrar.real("errorTesting/continuous", assumeLinear(continuous));
    registrar.real("errorTesting/derived", assumeLinear(derived));
    registrar.real("errorTesting/lowerBound", assumeLinear(lowerBound));
    registrar.real("errorTesting/upperBound", assumeLinear(upperBound));
    registrar.real("errorTesting/clamped", assumeLinear(clamped));
  }
}
