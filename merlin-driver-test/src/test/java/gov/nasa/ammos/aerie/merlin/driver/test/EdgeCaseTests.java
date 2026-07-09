package gov.nasa.ammos.aerie.merlin.driver.test;

import gov.nasa.ammos.aerie.merlin.driver.test.framework.Cell;
import gov.nasa.ammos.aerie.merlin.driver.test.framework.TestRegistrar;
import gov.nasa.ammos.aerie.simulation.protocol.DualSchedule;
import gov.nasa.ammos.aerie.simulation.protocol.Simulator;
import gov.nasa.jpl.aerie.merlin.driver.IncrementalSimAdapter;
import gov.nasa.jpl.aerie.merlin.driver.develop.MerlinDriverAdapter;
import gov.nasa.jpl.aerie.merlin.driver.engine.SimulationEngine;
import gov.nasa.jpl.aerie.merlin.driver.retracing.RetracingDriverAdapter;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static gov.nasa.ammos.aerie.merlin.driver.test.framework.ModelActions.call;
import static gov.nasa.ammos.aerie.merlin.driver.test.framework.ModelActions.delay;
import static gov.nasa.ammos.aerie.merlin.driver.test.framework.ModelActions.spawn;
import static gov.nasa.ammos.aerie.merlin.driver.test.framework.ModelActions.waitUntil;
import static gov.nasa.ammos.aerie.merlin.driver.test.property.IncrementalSimPropertyTests.assertLastSegmentsEqual;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.duration;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Unit.UNIT;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EdgeCaseTests {
  static final Simulator.Factory INCREMENTAL_SIMULATOR = IncrementalSimAdapter::new;
  static final Simulator.Factory REGULAR_SIMULATOR = MerlinDriverAdapter::new;
  static final Simulator.Factory RETRACING_SIMULATOR = RetracingDriverAdapter::new;

  private final MutableBoolean childShouldError = new MutableBoolean(false);

  private Model model;

  record Cells(
      Cell x,
      Cell y,
      Cell z,
      Cell history,
      Cell u,
      Cell linear
  ) {
    Cell lookup(String name) {
      return switch (name) {
        case "x" -> x;
        case "y" -> y;
        case "z" -> z;
        case "history" -> history;
        case "u" -> u;
        case "linear" -> linear;
        default -> throw new IllegalStateException("Unexpected value: " + name);
      };
    }
  }

  record NoRerunAssertion(String type, Optional<String> args) {}

  class Model {
    public final Cells cells;
    private final TestRegistrar model;
    private final List<NoRerunAssertion> assertions = new ArrayList<>();
    private final List<NoRerunAssertion> violations = new ArrayList<>();

    public Model() {
      model = new TestRegistrar();
      cells = new Cells(model.cell(), model.cell(), model.cell(), model.cell(), model.cell(), model.linearCell());

      model.resource("x", () -> cells.x.get().toString());
      model.resource("y", () -> cells.y.get().toString());
      model.resource("z", () -> cells.z.get().toString());
      model.resource("history", () -> cells.history.get().toString());
      model.resource("u", () -> cells.u.get().toString());

      activity("callee_activity", this::callee_activity);
      activity("caller_activity", this::caller_activity);
      activity("other_activity", this::other_activity);
      activity("activity", this::activity);
      activity("decomposing_activity", this::decomposing_activity);
      activity("child_activity", this::child_activity);
      activity("emit_event", this::emit_event);
      activity("read_topic", this::read_topic);
      activity("read_emit_three_times", this::read_emit_three_times);
      activity("parent_of_reading_child", this::parent_of_reading_child);
      activity("spawns_reading_child", this::spawns_reading_child);
      activity("reading_child", this::reading_child);
      activity("parent_of_read_emit_three_times", this::parent_of_read_emit_three_times);
      activity("delay_zero_between_spawns", this::delay_zero_between_spawns);
      activity("no_op", this::no_op);
      activity("spawns_anonymous_task", this::spawns_anonymous_task);
      activity("call_multiple", this::call_multiple);
      activity("call_then_read", this::call_then_read);
      activity("emit_and_delay", this::emit_and_delay);
      activity("await_x_greater_than", this::await_x_greater_than);
      activity("await_y_greater_than", this::await_y_greater_than);
      activity("await_condition_set_by_child", this::await_condition_set_by_child);
      activity("set_linear", this::set_linear);
      activity("read_and_await_condition", this::read_and_await_condition);
    }

    /* Activities */
    void caller_activity(String arg) {
      cells.x.emit(100);
      call(() -> this.callee_activity("99"));
      cells.x.emit(98);
    }

    void callee_activity(String arg) {
      cells.x.emit(arg);
    }

    void activity(String arg) {
      int step = Integer.parseInt(arg);
      cells.x.emit(cells.x.getRightmostNumber() - step);
      delay(duration(5, SECONDS));
      cells.x.emit(cells.x.getRightmostNumber() + step);
      delay(duration(5, SECONDS));
      cells.x.emit(cells.x.getRightmostNumber() + step);
      delay(duration(5, SECONDS));
      cells.x.emit(cells.x.getRightmostNumber() - step);
    }

    void decomposing_activity(String arg) {
      cells.x.emit(55);
      spawn(() -> this.child_activity(""));
      cells.x.emit(57);
      delay(SECOND);
      cells.x.emit(55);
      waitUntil(() -> cells.y.getNum() == 10);
    }

    void child_activity(String arg) {
      cells.y.emit(13);
      delay(SECOND);
      cells.y.emit(10);
    }

    void other_activity(String arg) {
      waitUntil(() -> cells.x.getNum() > 56);
      cells.y.emit("10");
      waitUntil(() -> cells.x.getNum() > 56);
      cells.y.emit("9");
      cells.y.emit(cells.y.getNum() / 3);
    }

    void emit_event(String arg) {
      final var args = arg.split(",");
      final var topic = args[0];
      final var value = args[1];
      final var cell = cells.lookup(topic);
      cell.emit(value);
    }

    void read_topic(String topic) {
      final var cell = cells.lookup(topic);
      cells.history.emit("[" + cell.get().toString() + "]");
    }

    void read_emit_three_times(String arg) {
      final var args = arg.split(",");
      final var readTopic = args[0];
      final var emitTopic = args[1];
      final var delaySeconds = Integer.parseInt(args[2]);
      final var readCell = cells.lookup(readTopic);
      final var writeCell = cells.lookup(emitTopic);
      for (int i = 0; i < 3; i++) {
        final String readValue = readCell.get().toString();
        writeCell.emit("[" + readValue + "]");
        if (i < 2) {
          delay(SECOND.times(delaySeconds));
        }
      }
    }

    void parent_of_reading_child(String arg) {
      cells.y.emit("1");
      call(() -> reading_child(""));
      cells.y.emit("2");
    }

    void spawns_reading_child(String arg) {
      cells.y.emit("1");
      spawn(() -> reading_child(""));
      cells.y.emit("2");
    }

    void reading_child(String arg) {
      if (childShouldError.getValue()) throw new RuntimeException("Reran reading_child");
      cells.history.emit("[" + cells.x.get().toString() + "]");
      cells.history.emit("[" + cells.y.get().toString() + "]");
      delay(SECONDS.times(cells.x.getNum()));
    }

    void parent_of_read_emit_three_times(String arg) {
      spawn(() -> read_emit_three_times(arg));
    }

    void delay_zero_between_spawns(String arg) {
      spawn(() -> conditional_decomposition("1"));
      cells.y.emit(800);
      delay(ZERO);
      spawn(() -> conditional_decomposition("2"));
    }

    void conditional_decomposition(String arg) {
      if (cells.x.getNum() == 1) {
        spawn(() -> reading_child(""));
      } else {
        spawn(() -> emit_event("u,2"));
      }
    }

    void no_op(String arg) {

    }

    void spawns_anonymous_task(String arg) {
      delay(SECOND);
      spawn(() -> {
        delay(SECOND);
        final var x = cells.x.getNum();
        cells.x.emit(55);
        cells.y.emit(x + 1);
      });
      delay(SECOND.times(2));
      final var x = cells.x.getNum();
      final var res = x * 100;
      cells.y.emit(res);
    }

    void call_multiple(String arg) {
      call(() -> {emit_and_delay("1,u,2");});
      call(() -> {emit_and_delay(cells.x.getNum() + ",u,3");});
      call(() -> {emit_and_delay("1,u,4");});
    }

    void emit_and_delay(String arg) {
      final var args = arg.split(",");
      final var delaySeconds = Integer.parseInt(args[0]);
      final var emitTopic = args[1];
      final var emitValue = args[2];
      delay(SECONDS.times(delaySeconds));
      cells.lookup(emitTopic).emit(emitValue);
    }

    void call_then_read(String arg) {
      cells.y.emit(7);
      call(() -> reading_child(""));
      cells.y.emit(cells.x.getNum());
    }

    void await_x_greater_than(String arg) {
      final var threshold = Integer.parseInt(arg);
      cells.u.emit("1");
      waitUntil(() -> cells.x.getNum() > threshold);
      cells.u.emit("2");
    }

    void await_y_greater_than(String arg) {
      final var threshold = Integer.parseInt(arg);
      cells.u.emit("1");
      waitUntil(() -> cells.y.getNum() > threshold);
      cells.u.emit("2");
    }

    void set_linear(String arg) {
      final var args = arg.split(",");
      final var rate = Double.parseDouble(args[0]);
      final var initial = Double.parseDouble(args[0]);
      cells.linear.setRate(rate);
      cells.linear.setInitialValue(initial);
    }

    void await_condition_set_by_child(String arg) {
      cells.x.emit(9);
      spawn(() -> {
        if (cells.y.getNum() == 1) {
          delay(SECONDS.times(20));
        }
        cells.x.emit(10);
        delay(SECONDS.times(3));
      });
      waitUntil(() -> cells.x.getNum() > 9);
      cells.x.emit(11);
      delay(SECONDS.times(5));
    }

    void read_and_await_condition(String arg) {
      final var currentValue = cells.linear.getLinear();
      final var targetValue = 100;
      waitUntil(atLatest -> {
        final var value = cells.linear.getLinear();
        if (value > targetValue) return Optional.of(ZERO);
        if (cells.linear.getRate() == 0.0) return Optional.empty();
        final var delta = targetValue - value;
        final var seconds = delta / cells.linear.getRate();
        final var duration = Duration.roundNearest(seconds, SECONDS);
        if (duration.noLongerThan(atLatest)) return Optional.of(duration);
        return Optional.empty();
      });
      cells.x.emit((int) currentValue);
    }

    /* Utility methods */

    void activity(String type, Consumer<String> effectModel) {
      model.activity(type, $ -> {
        for (final var assertion : assertions) {
          if (assertion.type.equals(type)) {
            if (assertion.args.isEmpty() || assertion.args.get().equals($)) {
              violations.add(assertion);
            }
          }
        }
        effectModel.accept($);
      });
    }

    void clearAssertions() {
      assertions.clear();
      violations.clear();
    }
    void assertNoRerun(String type) {
      assertions.add(new NoRerunAssertion(type, Optional.empty()));
    }
    void assertNoRerun(String type, String arg) {
      assertions.add(new NoRerunAssertion(type, Optional.of(arg)));
    }

    public ModelType<Unit, TestRegistrar.CellMap> asModelType() {
      return model.asModelType();
    }
  }

  @BeforeEach
  void setup() {
    model = new Model();
    childShouldError.setFalse();
  }

  @Test
  void test_incremental() {
    final var schedule = new DualSchedule();
    schedule.add(10, "callee_activity","1");
    schedule.add(15, "callee_activity", "2").thenUpdate("3");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("callee_activity", "1");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_more_complex_add_only() {
    final var schedule = new DualSchedule();
    schedule.add(10, "other_activity");
    schedule.add(20, "activity", "5");
    schedule.add(50, "caller_activity");
    schedule.thenAdd(60, "decomposing_activity");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("other_activity");
      $.assertNoRerun("activity");
      $.assertNoRerun("caller_activity");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_more_complex_remove_only() {
    final var schedule = new DualSchedule();
    schedule.add(10, "other_activity");
    schedule.add(20, "activity", "5");
    schedule.add(50, "caller_activity").thenDelete();

    Consumer<Model> assertions = $ -> {
      //$.assertNoRerun("other_activity");  // other_activity waits until x > 56, and that is done by caller_activity, so it needs to rerun
      $.assertNoRerun("activity");
      $.assertNoRerun("caller_activity");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_with_reads() {
    final var schedule = new DualSchedule();
    schedule.add(10, "other_activity");
    schedule.add(20, "activity", "4");
    schedule.add(110, "other_activity");
    schedule.add(120, "activity", "5").thenUpdate(119);

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("activity", "4");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_with_new_reads_of_old_topics() {
    final var schedule = new DualSchedule();
    schedule.add(10, "emit_event", "x,1");
    schedule.add(15, "read_topic", "x");
    schedule.thenAdd(16, "read_topic", "x");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_branching_rbt() {
    final var schedule = new DualSchedule();
    schedule.add(1, "emit_event", "x,1");
    schedule.add(5, "read_emit_three_times", "x,history,5");
    schedule.add(7, "read_emit_three_times", "x,history,5");
    schedule.add(11, "emit_event", "x,2");
    schedule.thenAdd(10, "emit_event", "x,1");
    schedule.thenAdd(15, "read_topic", "x");
    schedule.thenAdd(16, "read_topic", "x");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "x,2");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_with_reads_made_stale_dynamically() {
    final var schedule = new DualSchedule();
    schedule.add(10, "emit_event", "x,1");
    schedule.add(15, "read_topic", "x");
    schedule.thenAdd(11, "emit_event", "x,2");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "x,1");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_with_reads_made_stale_dynamically_with_durative_activities() {
    final var schedule = new DualSchedule();
    schedule.add(10, "read_emit_three_times", "x,y,5");
    schedule.add(12, "emit_event", "x,1");
    schedule.add(30, "read_topic", "y");
    schedule.thenAdd(13, "emit_event", "x,2");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "x,1");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_called_activity() {
    final var schedule = new DualSchedule();
    schedule.add(2, "emit_event", "z,1");
    schedule.add(10, "parent_of_reading_child");
    schedule.thenAdd(5, "emit_event", "x,1");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "z,1");
      // $.assertNoRerun("parent_of_reading_child");  // because parent_of_reading_child calls reading_child() (instead of spawns), the timing of events may be affected by the stale reading child, so it must be re-run
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_spawned_activity() {
    final var schedule = new DualSchedule();
    schedule.add(2, "emit_event", "z,1");
    schedule.add(10, "spawns_reading_child");
    schedule.thenAdd(5, "emit_event", "x,1");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "z,1");
      if (!SimulationEngine.alwaysRerunParentTasks) $.assertNoRerun("spawns_reading_child");
      childShouldError.setFalse(); // Child should rerun
    };

    runTest(schedule, assertions);
  }

  /** Identical plan, should not require rerunning child */
  @Test
  void test_spawned_activity_no_changes() {
    final var schedule = new DualSchedule();
    schedule.add(2, "emit_event", "z,1");
    schedule.add(10, "spawns_reading_child");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "z,1");
      $.assertNoRerun("spawns_reading_child");
      childShouldError.setTrue();
    };

    runTest(schedule, assertions);
  }

  /** Identical plan, should not require rerunning child */
  @Test
  void test_called_activity_no_changes() {
    final var schedule = new DualSchedule();
    schedule.add(2, "emit_event", "z,1");
    schedule.add(10, "parent_of_reading_child");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "z,1");
      $.assertNoRerun("parent_of_reading_child");
      childShouldError.setTrue();
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_restart_task_with_earlier_non_stale_read() {
    final var schedule = new DualSchedule();
    schedule.add(7, "emit_event", "x,1");
    schedule.add(8, "parent_of_read_emit_three_times", "x,history,5");
    schedule.thenAdd(9, "emit_event", "x,2");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "x,1");
      if (!SimulationEngine.alwaysRerunParentTasks) $.assertNoRerun("parent_of_read_emit_three_times");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_delay_zero_between_spawns() {
    final var schedule = new DualSchedule();
    schedule.add(2, "emit_event", "x,1").thenUpdate("x,2");
    schedule.add(3, "delay_zero_between_spawns");

    Consumer<Model> assertions = $ -> {
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_await_child_condition() {
    final var schedule = new DualSchedule();
    schedule.add(3, "await_condition_set_by_child");
    schedule.thenAdd(2, "emit_event", "y,1");

    Consumer<Model> assertions = $ -> {
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_called_activity_multiple() {
    final var schedule = new DualSchedule();
    schedule.add(10, "call_multiple");
    schedule.thenAdd(5, "emit_event", "x,1");

    Consumer<Model> assertions = $ -> {
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_condition_satisfied_at_new_time() {
    final var schedule = new DualSchedule();
    schedule.add(0, "emit_event", "x,0");
    schedule.add(10, "await_x_greater_than", "100");
    schedule.add(12, "emit_event", "x,101").thenUpdate(13);

    Consumer<Model> assertions = $ -> {
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_condition_satisfied_just_after_spawn() {
    final var schedule = new DualSchedule();
    schedule.add(0, "emit_event", "x,1");
    schedule.add(10, "await_y_greater_than", "1");
    schedule.add(12, "spawns_reading_child");

    Consumer<Model> assertions = $ -> {
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_call_then_read() {
    final var schedule = new DualSchedule();
    schedule.add(0, "emit_event", "z,1");
    schedule.add(10, "call_then_read", "1");
    schedule.thenAdd(5, "emit_event", "x,72");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("emit_event", "z,1");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_no_op() {
    final var schedule = new DualSchedule();
    schedule.add(2, "no_op");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("no_op");
    };

    runTest(schedule, assertions);
  }

  @Test
  void test_spawns_anonymous_subtask() {
    final var schedule = new DualSchedule();
    schedule.add(2, "spawns_anonymous_task");
    schedule.thenAdd(1, "emit_event", "x,72");

    Consumer<Model> assertions = $ -> {
      //$.assertNoRerun("spawns_anonymous_task");  // spawns_anonymous_task reads x after emit_event, so it needs to rerun
    };

    runTest(schedule, assertions);
  }

  @Disabled // This test depends on a "read subset of cell" feature that is out of scope for now
  @Test
  void test_tricky_condition() {
    final var schedule = new DualSchedule();
    schedule.add(0, "set_linear", "2,0").thenUpdate("1,10");
    schedule.add(10, "read_and_await_condition");

    Consumer<Model> assertions = $ -> {
      $.assertNoRerun("read_and_await_condition");
    };

    runTest(schedule, assertions);
  }

  // TODO test case: await condition when Z passed through the interval of interest between two simulation steps
  // TODO complex condition with multiple reads
  // TODO case where condition fires in the future, and is invalidated by an event before that future time arrives
  // TODO test expiry
  // TODO test anchors

  private void runTest(DualSchedule schedule, Consumer<Model> assertions) {
    model.clearAssertions();
    final var schedule1 = schedule.schedule1();
    final var schedule2 = schedule.schedule2();

    final var referenceSimulator = REGULAR_SIMULATOR.create(model.asModelType(), UNIT, Instant.EPOCH, HOUR);
    final var simulatorUnderTest = INCREMENTAL_SIMULATOR.create(model.asModelType(), UNIT, Instant.EPOCH, HOUR);
    {
      System.out.println("Reference simulation 1");
      final var expectedProfiles = referenceSimulator.simulate(schedule1).discreteProfiles();

      final var expected = new LinkedHashMap<String, String>();
      for (final var entry : expectedProfiles.entrySet()) {
        expected.put(entry.getKey(), entry.getValue().segments().getLast().dynamics().asString().get());
      }
      System.out.println("Expected last segment: " + expected);

      System.out.println("Test simulation 1");
      final var actualProfiles = simulatorUnderTest.simulate(schedule1).discreteProfiles();
      assertLastSegmentsEqual(expectedProfiles, actualProfiles);

    }

    {
      System.out.println("Reference simulation 2");
      final var expectedProfiles = referenceSimulator.simulate(schedule2).discreteProfiles();

      final var expected = new LinkedHashMap<String, String>();
      for (final var entry : expectedProfiles.entrySet()) {
        expected.put(entry.getKey(), entry.getValue().segments().getLast().dynamics().asString().get());
      }
      System.out.println("Expected last segment: " + expected);

      assertions.accept(model);
      System.out.println("Test simulation 2");
      final var retracingProfiles = simulatorUnderTest.simulate(schedule2).discreteProfiles();
      assertLastSegmentsEqual(expectedProfiles, retracingProfiles);
      assertEquals(List.of(), model.violations);
    }
  }
}
