package gov.nasa.ammos.aerie.merlin.driver.test.property;

import com.squareup.javapoet.CodeBlock;
import gov.nasa.ammos.aerie.merlin.driver.test.framework.Cell;
import gov.nasa.ammos.aerie.merlin.driver.test.framework.TestRegistrar;
import gov.nasa.ammos.aerie.simulation.protocol.DualSchedule;
import gov.nasa.jpl.aerie.merlin.protocol.model.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import static gov.nasa.ammos.aerie.merlin.driver.test.framework.ModelActions.call;
import static gov.nasa.ammos.aerie.merlin.driver.test.framework.ModelActions.delay;
import static gov.nasa.ammos.aerie.merlin.driver.test.framework.ModelActions.spawn;
import static gov.nasa.ammos.aerie.merlin.driver.test.property.IncrementalSimPropertyTests.printEffectModel;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

public record Scenario(
    // Cells
    Cell[] cells,
    List<DirectiveType> directiveTypes,
    Map<String, Trace.Owner> traces,
    TestRegistrar model,
    Instant startTime,
    Duration duration,
//    Schedule schedule1,
//    Schedule schedule2,
    DualSchedule schedule
    // DirectiveTypes (which may refer to cells)
    //   A directive type is a series of Actions, which may unfold lazily (the rule is it must be consistent across runs)
)
{
  void shrinkToTraces() {
    for (final var directiveType : directiveTypes) {
      directiveType.effectModel().shrinkToTrace(traces.get(directiveType.name()));
    }
  }

  void resetTraces() {
    for (final var directiveType : directiveTypes) {
      traces.put(directiveType.name(), new Trace.TraceImpl());
    }
  }

  @Override
  public String toString() {
    final var res = new StringBuilder();
    final var builder = CodeBlock.builder();
    builder.addStatement("final var model = new TestRegistrar()");
    builder.addStatement("SideBySideTest.Cell[] cells = new SideBySideTest.Cell[$L]", cells.length);
    builder.beginControlFlow("for (int i = 0; i < cells.length; i++)");
    builder.addStatement("cells[i] = model.cell()");
    builder.endControlFlow();
    builder.add(new DirectiveTypes(directiveTypes).toString(cells.length));
    builder.beginControlFlow("for (int i = 0; i < cells.length; i++)");
    builder.addStatement("final var cell = cells[i]");
    builder.addStatement("model.resource(\"cell\" + i, () -> cell.get().toString())");
    builder.endControlFlow();
    builder.addStatement("final var schedule = new DualSchedule()");

    for (final var entry : schedule.summarize()) {
      builder.add("schedule");
      if (entry.getLeft() != null) {
        builder.add(".add(duration($L, SECONDS), $S)", entry.getLeft().startOffset().in(SECONDS), entry.getLeft().directive().type());
        if (entry.getRight() != null) {
          switch (entry.getRight()) {
            case DualSchedule.Edit.Add e -> {
              throw new IllegalArgumentException("Cannot thenAdd on added activity");
            }
            case DualSchedule.Edit.Delete e -> {
              builder.add(".thenDelete()");
            }
            case DualSchedule.Edit.UpdateStart e -> {
              builder.add(".thenUpdate(duration($L, SECONDS))", e.newStartOffset().in(SECONDS));
            }
            case DualSchedule.Edit.UpdateArg e -> {
              builder.add(".thenUpdate($S)", e.newArg());
            }
          }
        }
      } else {
        final var add = (DualSchedule.Edit.Add) entry.getRight();
        builder.add(".thenAdd(duration($L, SECONDS), $S)", add.startOffset().in(SECONDS), add.directiveType());
      }
      builder.add(";\n");
    }

//    for (final var entry : schedule.schedule1().entries()) {
//      builder.addStatement("schedule1.add(duration($L, SECONDS), $S)", entry.startOffset().in(SECONDS), entry.directive().type());
//    }
//    builder.addStatement("final var schedule2 = new DualSchedule()");
//    for (final var entry : schedule.schedule2().entries()) {
//      builder.addStatement("schedule2.add(duration($L, SECONDS), $S)", entry.startOffset().in(SECONDS), entry.directive().type());
//    }
//    final var schedule2CodeBlock = schedule.schedule2().entries()
//                                            .stream()
//                                            .map(directiveType -> CodeBlock
//                                                .builder()
//                                                .add(
//                                                    "\nPair.of(duration($L, SECONDS), new Directive($S, Map.of()))",
//                                                    directiveType.startOffset().in(SECONDS),
//                                                    directiveType.directive().type()))
//                                            .reduce((x, y) -> x.add(",").add(y.build()))
//                                            .orElse(CodeBlock.builder());
//    builder.addStatement("Schedule schedule2 = Schedule.build($L)", schedule2CodeBlock.build());

    res.append(builder.build());
    return res.toString();
  }

  public static void interpret(EffectModel effectModel, Cell[] cells, Trace.Writer tracer) {
    for (int i = 0; i < effectModel.steps().size(); i++) {
      final int stepIndex = i;
      switch (effectModel.steps().get(i)) {
        case Step.CallDirective s -> {
        }
        case Step.CallTask s -> {
          call(() -> interpret(s.task, cells, tracer.call(stepIndex)));
        }
        case Step.Delay s -> {
          delay(s.duration());
        }
        case Step.Emit s -> {
          cells[Math.floorMod(s.topic(), cells.length)].emit(s.value());
        }
        case Step.Read s -> {
          if (rightmostNumber(cells[Math.floorMod(s.topic(), cells.length)].get().toString())
              < s.branch().threshold()) {
            interpret(s.branch().left(), cells, tracer.visitLeft(i));
          } else {
            interpret(s.branch().right(), cells, tracer.visitRight(i));
          }
        }
        case Step.SpawnDirective s -> {
        }
        case Step.SpawnTask s -> {
          spawn(() -> interpret(s.task, cells, tracer.spawn(stepIndex)));
        }
        case Step.WaitUntil s -> {
        }
      }
    }


  }

  public record DirectiveType(String name, List<String> parameters, EffectModel effectModel) {
    Map<String, SerializedValue> genArgs(long seed) {
      final var res = new LinkedHashMap<String, SerializedValue>();
      for (final var param : parameters) {
        res.put(param, SerializedValue.NULL);
      }
      return res;
    }

    public String toString(final int numCells) {
      final CodeBlock.Builder builder = CodeBlock.builder();
      if (effectModel.steps.isEmpty()) {
        builder.addStatement("model.activity($S, it -> {})", name);
      } else {
        builder.beginControlFlow("model.activity($S, it ->", name);
        builder.add(printEffectModel(effectModel, numCells));
        builder.endControlFlow(")");
      }
      return builder.build().toString();
    }
  }

  public record DirectiveTypes(List<DirectiveType> directiveTypes) {
    public String toString(final int numCells) {
      final var res = new StringBuilder();
      var first = true;
      for (final var directiveType : directiveTypes) {
        if (!first) res.append("\n");
        res.append(directiveType.toString(numCells));
        first = false;
      }
      return res.toString();
    }
  }

  public record EffectModel(ArrayList<Step> steps) {
    public static EffectModel empty() {
      return new EffectModel(new ArrayList<>());
    }

    public void shrinkToTrace(Trace.Reader trace) {
      int i = 0;
      int inlineCount = 0;
      while (i + inlineCount < steps.size()) {
        if (steps.get(i + inlineCount) instanceof Step.Read s) {
          if (trace.visitedLeft(i)) {
            s.branch().left().shrinkToTrace(trace.getLeft(i));
          } else {
            s.branch().left().steps().clear();
          }
          if (trace.visitedRight(i)) {
            s.branch().right().shrinkToTrace(trace.getRight(i));
          } else {
            s.branch().right().steps().clear();
          }
          if (s.branch().left().steps().isEmpty()) {
            steps.addAll(i + inlineCount + 1, s.branch().right().steps());
            inlineCount += s.branch().right().steps().size();
            s.branch().right().steps().clear();
          } else if (s.branch().right().steps().isEmpty()) {
            steps.addAll(i + inlineCount + 1, s.branch().left().steps());
            inlineCount += s.branch().left().steps().size();
            s.branch().left().steps().clear();
          }
        } else if (steps.get(i + inlineCount) instanceof Step.SpawnTask s) {
          s.task().shrinkToTrace(trace.getSpawn(i));
        } else if (steps.get(i + inlineCount) instanceof Step.CallTask s) {
          s.task().shrinkToTrace(trace.getCall(i));
        }
        i++;
      }
    }
  }

  public record Directive() {

  }

  record Branch(int threshold, EffectModel left, EffectModel right) {}

  sealed interface Step {
    record Emit(String value, int topic) implements Step {}

    record Read(int topic, Branch branch) implements Step {}

    record Delay(Duration duration) implements Step {}

    record WaitUntil(Condition condition) implements Step {}

    record SpawnTask(EffectModel task) implements Step {}

    record SpawnDirective(Directive directive) implements Step {}

    record CallTask(EffectModel task) implements Step {}

    record CallDirective(Directive directive) implements Step {}
  }

  public static Arbitrary<DirectiveType> directiveType(Arbitrary<Integer> atoms) {
    return Arbitraries
        .lazyOf(() -> atoms.flatMap(name -> effectModels(atoms).map($ -> new DirectiveType(
            "DT" + Math.abs(name),
            List.of(),
            $))));
  }

  public static Arbitrary<EffectModel> effectModels(Arbitrary<Integer> atoms) {
    return Arbitraries
        .lazyOf(
            () -> Arbitraries.just(EffectModel.empty()),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> singleStep(atoms),
            () -> concatenateEffectModels(atoms),
            () -> concatenateEffectModels(atoms),
            () -> concatenateEffectModels(atoms),
            () -> concatenateEffectModels(atoms),
            () -> concatenateEffectModels(atoms),
            () -> concatenateEffectModels(atoms),
            () -> branchOnRead(atoms),
            () -> branchOnRead(atoms),
            () -> wrapInSpawn(atoms),
            () -> wrapInCall(atoms)
        );
  }

  private static Arbitrary<EffectModel> wrapInSpawn(Arbitrary<Integer> atoms) {
    return effectModels(atoms)
        .map($ -> {
          final ArrayList<Step> steps = new ArrayList<>();
          steps.add(new Step.SpawnTask($));
          return new EffectModel(steps);
        });
  }

  private static Arbitrary<EffectModel> wrapInCall(Arbitrary<Integer> atoms) {
    return effectModels(atoms)
        .map($ -> {
          final ArrayList<Step> steps = new ArrayList<>();
          steps.add(new Step.CallTask($));
          return new EffectModel(steps);
        });
  }

  private static Arbitrary<EffectModel> concatenateEffectModels(Arbitrary<Integer> atoms) {
    return effectModels(atoms).tuple2().map($ -> {
      final var steps = new ArrayList<>($.get1().steps());
      steps.addAll($.get2().steps());
      return new EffectModel(steps);
    });
  }

  private static Arbitrary<EffectModel> branchOnRead(Arbitrary<Integer> atoms) {
    return atoms.tuple2().flatMap(
        $ -> effectModels(atoms).tuple2().map(e -> {
          final int topicSelector = Math.abs($.get1());
          final int threshold = Math.abs($.get2());
          final var steps = new ArrayList<Step>();
          steps.add(new Step.Read(topicSelector, new Branch(threshold, e.get1(), e.get2())));
          return new EffectModel(steps);
        })
    );
  }

  private static Arbitrary<EffectModel> singleStep(Arbitrary<Integer> atoms) {
    return atoms.tuple3().map($ -> {
      final var stepSelector = Math.floorMod($.get1(), 3);
      final int topicSelector = $.get2();
      final String message = String.valueOf(Math.abs($.get3()));
      final var step = switch (stepSelector) {
        case 0 -> new Step.Emit(message, topicSelector);
        case 1 -> new Step.Delay(SECOND);
        case 2 -> new Step.WaitUntil(null);
        default -> throw new IllegalStateException("Unexpected value: " + stepSelector);
      };
      final var steps = new ArrayList<Step>();
      steps.add(step);
      return new EffectModel(steps);
    });
  }

  public static int rightmostNumber(String s) {
    StringBuilder result = new StringBuilder();
    boolean startedNumber = false;
    for (int i = 0; i < s.length(); i++) {
      final var c = s.substring(s.length() - i - 1, s.length() - i);
      if (isDigit(c)) {
        startedNumber = true;
        result.insert(0, c);
      } else if (startedNumber) {
        break;
      }
    }
    if (!result.isEmpty()) {
      return Integer.parseInt(result.toString());
    } else {
      return -1;
    }
  }

  public static boolean isDigit(String s) {
    if (s.length() != 1) throw new IllegalArgumentException(s);
    return "0123456789".contains(s);
  }
}
