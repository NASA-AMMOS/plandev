package gov.nasa.jpl.aerie.contrib.streamline.modeling.black_box;

import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.function.DoubleUnaryOperator;
import java.util.function.UnaryOperator;

public interface Differentiable extends Dynamics<Double, Differentiable> {
  Differentiable derivative();

  static Differentiable constant(double value) {
    return new Differentiable() {
      @Override
      public Differentiable derivative() {
        return constant(0);
      }

      @Override
      public Double extract() {
        return value;
      }

      @Override
      public Differentiable step(Duration t) {
        return this;
      }
    };
  }

  static Differentiable differentiable(Differentiable d, DoubleUnaryOperator f, UnaryOperator<Differentiable> fPrime) {
    return new Differentiable() {
      @Override
      public Differentiable derivative() {
        return fPrime.apply(d).multiply(d.derivative());
      }

      @Override
      public Double extract() {
        return f.applyAsDouble(d.extract());
      }

      @Override
      public Differentiable step(final Duration t) {
        return differentiable(d.step(t), f, fPrime);
      }
    };
  }

  default Differentiable add(Differentiable d) {
    return new Differentiable() {
      @Override
      public Differentiable derivative() {
        return Differentiable.this.derivative().add(d.derivative());
      }

      @Override
      public Double extract() {
        return Differentiable.this.extract() + d.extract();
      }

      @Override
      public Differentiable step(final Duration t) {
        return Differentiable.this.step(t).add(d.step(t));
      }
    };
  }

  default Differentiable subtract(Differentiable d) {
    return Differentiable.this.add(d.multiply(-1));
  }

  default Differentiable multiply(double scalar) {
    return new Differentiable() {
      @Override
      public Differentiable derivative() {
        return Differentiable.this.derivative().multiply(scalar);
      }

      @Override
      public Double extract() {
        return Differentiable.this.derivative().extract() * scalar;
      }

      @Override
      public Differentiable step(final Duration t) {
        return Differentiable.this.derivative().step(t).multiply(scalar);
      }
    };
  }

  default Differentiable multiply(Differentiable d) {
    return new Differentiable() {
      @Override
      public Differentiable derivative() {
        return Differentiable.this.derivative().multiply(d).add(Differentiable.this.multiply(d.derivative()));
      }

      @Override
      public Double extract() {
        return Differentiable.this.extract() * d.extract();
      }

      @Override
      public Differentiable step(final Duration t) {
        return Differentiable.this.step(t).multiply(d.step(t));
      }
    };
  }

  default Differentiable divide(double scalar) {
    return multiply(1 / scalar);
  }

  default Differentiable divide(Differentiable d) {
    return this.multiply(d.power(-1));
  }

  default Differentiable power(int exponent) {
    return differentiable(this, x -> Math.pow(x, exponent), d -> d.power(exponent - 1).multiply(exponent));
  }
}
