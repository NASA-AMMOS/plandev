package gov.nasa.jpl.aerie.merlin.framework.resources.real;

import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.framework.Resource;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;

import java.util.Objects;

public interface RealResource extends Resource<RealDynamics> {
  static RealResource scaleBy(final double scalar, final RealResource resource) {
    Objects.requireNonNull(resource);

    return () -> resource.getDynamics().scaledBy(scalar);
  }

  static RealResource add(final RealResource left, final RealResource right) {
    Objects.requireNonNull(left);
    Objects.requireNonNull(right);

    return () -> left.getDynamics().plus(right.getDynamics());
  }

  static RealResource subtract(final RealResource left, final RealResource right) {
    Objects.requireNonNull(left);
    Objects.requireNonNull(right);

    return () -> left.getDynamics().minus(right.getDynamics());
  }


  default RealResource plus(final RealResource other) {
    return RealResource.add(this, other);
  }

  default RealResource minus(final RealResource other) {
    return RealResource.subtract(this, other);
  }

  default RealResource scaledBy(final double scalar) {
    return RealResource.scaleBy(scalar, this);
  }


  default double get() {
    return this.getDynamics().initial;
  }

  default Condition isBetween(final double lower, final double upper) {
    return (positive, atEarliest, atLatest) -> {
      final var dynamics = this.getDynamics();

      return (positive)
          ? dynamics.whenBetween(lower, upper, atEarliest, atLatest)
          : dynamics.whenNotBetween(lower, upper, atEarliest, atLatest);
    };
  }
}
