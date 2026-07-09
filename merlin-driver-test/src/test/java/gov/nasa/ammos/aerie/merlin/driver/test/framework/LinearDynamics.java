package gov.nasa.ammos.aerie.merlin.driver.test.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.mutable.MutableObject;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

public record LinearDynamics(double rate, double initialValue) {
  public record LinearDynamicsEffect(Double newRate, Double newValue) {
    static LinearDynamicsEffect empty() {
      return new LinearDynamicsEffect(null, null);
    }
    boolean isEmpty() {
      return newRate == null && newValue == null;
    }
  }

  public static CellId<MutableObject<LinearDynamics>> allocate(final Initializer builder, final Topic<LinearDynamicsEffect> topic) {
    return builder.allocate(
        new MutableObject<>(new LinearDynamics(0, 0)),
        new CellType<>() {
          @Override
          public EffectTrait<LinearDynamicsEffect> getEffectType() {
            return new EffectTrait<>() {
              @Override
              public LinearDynamicsEffect empty() {
                return LinearDynamicsEffect.empty();
              }

              @Override
              public LinearDynamicsEffect sequentially(
                  final LinearDynamicsEffect prefix,
                  final LinearDynamicsEffect suffix)
              {
                if (suffix.isEmpty()) {
                  return prefix;
                } else {
                  return suffix;
                }
              }

              @Override
              public LinearDynamicsEffect concurrently(
                  final LinearDynamicsEffect left,
                  final LinearDynamicsEffect right)
              {
                if (left.isEmpty()) return right;
                if (right.isEmpty()) return left;
                throw new IllegalArgumentException("Concurrent composition of non-empty linear effects: "
                                                   + left
                                                   + " | "
                                                   + right);
              }
            };
          }

          @Override
          public MutableObject<LinearDynamics> duplicate(final MutableObject<LinearDynamics> mutableObject) {
            return new MutableObject<>(mutableObject.getValue());
          }

          @Override
          public void apply(final MutableObject<LinearDynamics> mutableObject, final LinearDynamicsEffect o) {
            final LinearDynamics currentDynamics = mutableObject.getValue();
            mutableObject.setValue(new LinearDynamics(o.newRate() == null ? currentDynamics.rate() : o.newRate(), o.newValue() == null ? currentDynamics.initialValue() : o.newValue()));
          }

          @Override
          public void step(final MutableObject<LinearDynamics> mutableObject, final Duration duration) {
            final LinearDynamics currentDynamics = mutableObject.getValue();
            mutableObject.setValue(
                new LinearDynamics(
                    currentDynamics.rate(),
                    currentDynamics.initialValue() + (duration.ratioOver(SECONDS) * currentDynamics.rate)));
          }
        },
        $ -> $,
        topic);
  }
}
