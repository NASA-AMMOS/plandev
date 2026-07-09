package gov.nasa.ammos.aerie.merlin.driver.test.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.Objects;

import static gov.nasa.ammos.aerie.merlin.driver.test.property.Scenario.rightmostNumber;

public record Cell(Topic<String> topic, Topic<LinearDynamics.LinearDynamicsEffect> linearTopic, boolean isLinear) {
  public static Cell of()
  {
    return new Cell(new Topic<>(), new Topic<>(), false);
  }

  public static Cell ofLinear()
  {
    return new Cell(new Topic<>(), new Topic<>(), true);
  }

  public void emit(String event) {
    TestContext.get().scheduler().emit(event, this.topic);
  }

  public void emit(int number) {
    this.emit(String.valueOf(number));
  }

  public void setRate(final double newRate) {
    TestContext.get().scheduler().emit(new LinearDynamics.LinearDynamicsEffect(newRate, null), this.linearTopic);
  }

  public void setInitialValue(final double newInitialValue) {
    TestContext.get().scheduler().emit(
        new LinearDynamics.LinearDynamicsEffect(null, newInitialValue),
        this.linearTopic);
  }

  public double getLinear() {
    final var context = TestContext.get();
    final var scheduler = context.scheduler();
    final var cellId = context.cells().get(this);
    final MutableObject<LinearDynamics> state = (MutableObject<LinearDynamics>) scheduler.get(Objects.requireNonNull(
        cellId));
    return state.getValue().initialValue();
  }

  public double getRate() {
    final var context = TestContext.get();
    final var scheduler = context.scheduler();
    final var cellId = context.cells().get(this);
    final MutableObject<LinearDynamics> state = (MutableObject<LinearDynamics>) scheduler.get(Objects.requireNonNull(
        cellId));
    return state.getValue().rate();
  }

  public int getRightmostNumber() {
    return rightmostNumber(this.get().toString());
  }

  public int getNum() {
    for (final var entry : this.get().timeline.reversed()) {
      if (!(entry instanceof History.TimePoint.Commit e)) continue;
      final int num = rightmostNumber(e.toString());
      if (num != -1) return num;
    }
    return 0;
  }

  @SuppressWarnings("unchecked")
  public History get() {
    final var context = TestContext.get();
    final var scheduler = context.scheduler();
    final var cellId = context.cells().get(this);
    final MutableObject<History> state = (MutableObject<History>) scheduler.get(Objects.requireNonNull(cellId));
    return state.getValue();
  }
}
