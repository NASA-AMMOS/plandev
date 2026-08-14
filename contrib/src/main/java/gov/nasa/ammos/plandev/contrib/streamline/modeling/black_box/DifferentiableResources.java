package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad;
import gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.Polynomial;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Collection;

import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.map;
import static gov.nasa.jpl.aerie.contrib.streamline.core.monads.ResourceMonad.reduce;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Naming.name;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box.Differentiable.differentiable;
import static java.util.Arrays.stream;

public final class DifferentiableResources {
    private DifferentiableResources() {}

    public static Resource<Differentiable> constant(double value) {
        var result = ResourceMonad.pure(Differentiable.constant(value));
        name(result, Double.toString(value));
        return result;
    }

    public static Differentiable asDifferentiable(Polynomial polynomial) {
        return new Differentiable() {
            @Override
            public Differentiable derivative() {
                return asDifferentiable(polynomial.derivative());
            }

            @Override
            public Double extract() {
                return polynomial.extract();
            }

            @Override
            public Differentiable step(final Duration t) {
                return asDifferentiable(polynomial.step(t));
            }
        };
    }

    public static Resource<Differentiable> asDifferentiable(Resource<Polynomial> polynomial) {
        var result = map(polynomial, DifferentiableResources::asDifferentiable);
        name(result, "%s", polynomial);
        return result;
    }

    @SafeVarargs
    public static Resource<Differentiable> add(Resource<Differentiable>... summands) {
        return sum(stream(summands).toList());
    }

    public static Resource<Differentiable> sum(Collection<Resource<Differentiable>> summands) {
        return reduce(summands, Differentiable.constant(0), Differentiable::add, "Sum");
    }

    public static Resource<Differentiable> subtract(Resource<Differentiable> left, Resource<Differentiable> right) {
        var result = map(left, right, Differentiable::subtract);
        name(result, "(%s) - (%s)", left, right);
        return result;
    }

    @SafeVarargs
    public static Resource<Differentiable> multiply(Resource<Differentiable>... factors) {
        return product(stream(factors).toList());
    }

    public static Resource<Differentiable> product(Collection<Resource<Differentiable>> factors) {
        return reduce(factors, Differentiable.constant(0), Differentiable::multiply, "Product");
    }

    public static Resource<Differentiable> divide(Resource<Differentiable> left, Resource<Differentiable> right) {
        var result = map(left, right, Differentiable::subtract);
        name(result, "(%s) / (%s)", left, right);
        return result;
    }

    private static Differentiable cos(Differentiable argument) {
      return differentiable(argument, Math::cos, d -> sin(d).multiply(-1));
    }

    private static Differentiable sin(Differentiable argument) {
      return differentiable(argument, Math::sin, DifferentiableResources::cos);
    }

    private static Differentiable exp(Differentiable argument) {
      return differentiable(argument, Math::exp, DifferentiableResources::exp);
    }

    public static Resource<Differentiable> sin(Resource<Differentiable> argument) {
        var result = map(argument, DifferentiableResources::sin);
        name(result, "sin(%s)", argument);
        return result;
    }

    public static Resource<Differentiable> cos(Resource<Differentiable> argument) {
        var result = map(argument, DifferentiableResources::cos);
        name(result, "cos(%s)", argument);
        return result;
    }

    public static Resource<Differentiable> exp(Resource<Differentiable> argument) {
        var result = map(argument, DifferentiableResources::exp);
        name(result, "exp(%s)", argument);
        return result;
    }
}
