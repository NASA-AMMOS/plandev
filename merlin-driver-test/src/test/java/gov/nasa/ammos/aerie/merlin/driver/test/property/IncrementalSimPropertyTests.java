package gov.nasa.ammos.aerie.merlin.driver.test.property;

import com.squareup.javapoet.CodeBlock;
import gov.nasa.ammos.aerie.merlin.driver.test.framework.Cell;
import gov.nasa.ammos.aerie.merlin.driver.test.framework.TestRegistrar;
import gov.nasa.ammos.aerie.simulation.protocol.DualSchedule;
import gov.nasa.ammos.aerie.simulation.protocol.ResourceProfile;
import gov.nasa.ammos.aerie.simulation.protocol.Simulator;
import gov.nasa.jpl.aerie.merlin.driver.IncrementalSimAdapter;
import gov.nasa.jpl.aerie.merlin.driver.develop.MerlinDriverAdapter;
import gov.nasa.jpl.aerie.merlin.driver.retracing.RetracingDriverAdapter;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.commons.lang3.mutable.MutableBoolean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import static gov.nasa.ammos.aerie.merlin.driver.test.property.Scenario.directiveType;
import static gov.nasa.ammos.aerie.merlin.driver.test.property.Scenario.effectModels;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.duration;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class IncrementalSimPropertyTests {
  private static final Simulator.Factory REGULAR_SIM_FACTORY = MerlinDriverAdapter::new;
  private static final Simulator.Factory INCREMENTAL_SIM_FACTORY = IncrementalSimAdapter::new;

  private static boolean failed = false;

  @Property
  @Label("Incremental re-simulation should be consistent with regular simulation")
  public void incrementalSimulationMatchesRegularSimulation(@ForAll("scenarios") Scenario scenario) {
    final var incrementalSimulator = INCREMENTAL_SIM_FACTORY.create(
        scenario.model().asModelType(),
        Unit.UNIT,
        scenario.startTime(),
        scenario.duration());
    final var regularSimulator = REGULAR_SIM_FACTORY.create(
        scenario.model().asModelType(),
        Unit.UNIT,
        scenario.startTime(),
        scenario.duration());

    System.out.println("Testing with schedule of size: " + scenario.schedule().schedule1().size());

    regularSimulator.simulate(scenario.schedule().schedule1());
    incrementalSimulator.simulate(scenario.schedule().schedule1());

    final var regularProfiles = regularSimulator.simulate(scenario.schedule().schedule2()).discreteProfiles();

    MutableBoolean cancelSim = new MutableBoolean(false);

    final var incrementalProfiles = incrementalSimulator
        .simulate(scenario.schedule().schedule2(), cancelSim::getValue)
        .getDiscreteProfiles();

//    new Timer().schedule(new TimerTask() {
//      @Override
//      public void run() {
//        cancelSim.setTrue();
//        System.out.println(scenario);
//      }
//    }, 30 * 1000);

    if (!lastSegmentsEqual(regularProfiles, incrementalProfiles)) {
      if (!failed) {
        System.out.println("Encountered first failure");
      }
      failed = true;
      scenario.resetTraces();
      regularSimulator.simulate(scenario.schedule().schedule2());
      scenario.shrinkToTraces();
      System.out.println(scenario);
      assertEquals(regularProfiles, incrementalProfiles);
    }
  }

  public static boolean lastSegmentsEqual(
      final Map<String, ResourceProfile<SerializedValue>> regularProfiles,
      final Map<String, ResourceProfile<SerializedValue>> incrementalProfiles
  ) {
    final var expected = new LinkedHashMap<String, String>();
    for (final var entry : regularProfiles.entrySet()) {
      expected.put(entry.getKey(), entry.getValue().segments().getLast().dynamics().asString().get());
    }
    final var actual = new LinkedHashMap<String, String>();
    for (final var entry : incrementalProfiles.entrySet()) {
      actual.put(entry.getKey(), entry.getValue().segments().getLast().dynamics().asString().get());
    }
    return expected.equals(actual);
  }

  public static void assertLastSegmentsEqual(
      final Map<String, ResourceProfile<SerializedValue>> regularProfiles,
      final Map<String, ResourceProfile<SerializedValue>> incrementalProfiles
  ) {
    final var expected = new LinkedHashMap<String, String>();
    for (final var entry : regularProfiles.entrySet()) {
      expected.put(entry.getKey(), entry.getValue().segments().getLast().dynamics().asString().get());
    }
    final var actual = new LinkedHashMap<String, String>();
    for (final var entry : incrementalProfiles.entrySet()) {
      actual.put(entry.getKey(), entry.getValue().segments().getLast().dynamics().asString().get());
    }
    assertEquals(expected, actual);
  }

  @Provide("scenarios")
  static Arbitrary<Scenario> scenarios() {
    return scenario(Arbitraries.integers());
  }

  static Arbitrary<DualSchedule> schedules(int numDirectiveTypes) {
    return Arbitraries.integers().flatMap(size -> Arbitraries
        .integers()
        .list()
        .ofSize(Math.floorMod(size, 100))
        .map($ -> $.stream().map(it -> duration(Math.floorMod(it, 3600), SECONDS)).toList())).map(allStartOffsets -> {

      final var startOffsets = new ArrayList<Duration>();
      final var additionalStartOffsets = new ArrayList<>(allStartOffsets);
      final long schedule1Size = Math.round(allStartOffsets.size() * 0.8);
      for (long i = 0; i < schedule1Size; i++) {
        startOffsets.add(additionalStartOffsets.removeLast());
      }

      // For each activity type
      DualSchedule schedule = new DualSchedule();

      for (int i = 0; i < startOffsets.size(); i++) {
        final var startOffset = startOffsets.get(i);
        final var name = "DT" + ((i % numDirectiveTypes) + 1);
        schedule.add(startOffset, name);
      }

      // Generate random edits to that schedule

      // Deletes
      int numDeletes = schedule.schedule1().size() / 4;
      for (int i = 0; i < numDeletes; i++) {
        schedule.thenDelete(schedule.schedule2().entries().getLast().id());
      }
      // Select number of deletes (must be less than or equal to the number of activities in the schedule)
      // Select which activities to delete

      // Updates
      // Select number of updates (must be less than or equal to the number of activities in the schedule)
      // Select which activities to update
      // Select time delta
      // TODO change parameters

      int numUpdates = schedule.schedule2().entries().size() / 2;
      for (int i = 0; i < numUpdates; i++) {
        final var entry = schedule.schedule2().entries().get(schedule.schedule2().entries().size() - i - 1);
        schedule.thenUpdate(entry.id(), entry.startOffset().plus(SECOND));
      }

      // Additions
      for (final var startOffset : additionalStartOffsets) {
        schedule.thenAdd(startOffset, "DT1");
      }

//      schedule1.entries().sort(Comparator.comparing(Schedule.ScheduleEntry::startOffset));
//      schedule2.entries().sort(Comparator.comparing(Schedule.ScheduleEntry::startOffset));
      return schedule;
    });
  }

  static Arbitrary<Scenario> scenario(Arbitrary<Integer> integers) {
    return Arbitraries
        .lazyOf(() -> integers.tuple3().flatMap(ints -> {
          final var numCells = 1 + Math.floorMod(ints.get1(), 10);
          final var numDirectiveTypes = 1 + Math.floorMod(ints.get2(), 4);

          return
              directiveTypes(numDirectiveTypes, integers).flatMap(directiveTypes -> schedules(numDirectiveTypes).map(
                  schedules -> {
                    final var model = new TestRegistrar();
                    Cell[] cells = new Cell[numCells];
                    for (int i = 0; i < cells.length; i++) {
                      cells[i] = model.cell();
                    }

                    Map<String, Trace.Owner> tracers = new LinkedHashMap<>();
                    for (final var directiveType : directiveTypes.directiveTypes()) {
                      tracers.put(directiveType.name(), new Trace.TraceImpl());
                      model.activity(directiveType.name(), $ -> {
                        Scenario.interpret(
                            directiveType.effectModel(),
                            cells,
                            tracers.get(directiveType.name()));
                      });
                    }

                    for (int i = 0; i < cells.length; i++) {
                      final var cell = cells[i];
                      model.resource("cell" + i, () -> cell.get().toString());
                    }

                    // Generate a random schedule
                    // TODO compute dependencies
                    return new Scenario(
                        cells,
                        directiveTypes.directiveTypes(),
                        tracers,
                        model,
                        Instant.EPOCH,
                        Duration.HOUR,
                        schedules);
                  }));
        }));
  }

  static Arbitrary<Scenario.DirectiveTypes> directiveTypes(int numDirectiveTypes, Arbitrary<Integer> integers) {
    return directiveType(integers).list().ofSize(numDirectiveTypes).map($ -> {
      final List<Scenario.DirectiveType> res = new ArrayList<>();
      for (int i = 0; i < $.size(); i++) {
        final var dt = $.get(i);
        res.add(new Scenario.DirectiveType("DT" + (i + 1), dt.parameters(), dt.effectModel()));
      }
      return new Scenario.DirectiveTypes(res);
    });
  }

  @Provide("effectModel")
  static Arbitrary<Scenario.EffectModel> effectModel() {
    return effectModels(Arbitraries.integers());
  }

  static CodeBlock printEffectModel(Scenario.EffectModel effectModel, int numCells) {
    final var builder = CodeBlock.builder();
    for (final var step : effectModel.steps()) {
      switch (step) {
        case Scenario.Step.CallDirective s -> {
          builder.addStatement("callDirective()");
        }
        case Scenario.Step.CallTask s -> {
          builder.beginControlFlow("call(() ->");
          builder.add(printEffectModel(s.task(), numCells));
          builder.endControlFlow(")");
        }
        case Scenario.Step.Delay s -> {
          builder.addStatement("delay(SECOND)");
        }
        case Scenario.Step.Emit s -> {
          builder.addStatement("$L.emit($S)", "cells[" + Math.floorMod(s.topic(), numCells) + "]", s.value());
        }
        case Scenario.Step.Read s -> {
          if (s.branch().left().steps().isEmpty() && s.branch().right().steps().isEmpty()) {
            builder.addStatement("$L.get()", "cells[" + Math.floorMod(s.topic(), numCells) + "]");
          } else if (!s.branch().left().steps().isEmpty()) {
            builder.beginControlFlow(
                "if (rightmostNumber($L.get().toString()) < $L)",
                "cells[" + Math.floorMod(s.topic(), numCells) + "]",
                s.branch().threshold());
            builder.add(printEffectModel(s.branch().left(), numCells));

            if (s.branch().right().steps().isEmpty()) {
              builder.endControlFlow();
            } else {
              builder.nextControlFlow("else");
              builder.add(printEffectModel(s.branch().right(), numCells));
              builder.endControlFlow();
            }
          } else {
            builder.beginControlFlow(
                "if (rightmostNumber($L.get().toString()) >= $L)",
                "cells[" + Math.floorMod(s.topic(), numCells) + "]",
                s.branch().threshold());
            builder.add(printEffectModel(s.branch().right(), numCells));
            builder.endControlFlow();
          }
        }
        case Scenario.Step.SpawnDirective s -> {
          builder.addStatement("spawnDirective()");
        }
        case Scenario.Step.SpawnTask s -> {
          builder.beginControlFlow("spawn(() ->");
          builder.add(printEffectModel(s.task(), numCells));
          builder.endControlFlow(")");
        }
        case Scenario.Step.WaitUntil s -> {
          builder.addStatement("waitUntil()");
        }
      }
    }
    return builder.build();
  }
}
