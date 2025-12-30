package gov.nasa.jpl.aerie.scheduler.simulation;

import gov.nasa.jpl.aerie.constraints.model.ActivityInstance;
import gov.nasa.jpl.aerie.constraints.model.EvaluationEnvironment;
import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Interval;
import gov.nasa.jpl.aerie.constraints.tree.ActivitySpan;
import gov.nasa.jpl.aerie.constraints.tree.DiscreteValue;
import gov.nasa.jpl.aerie.constraints.tree.ListExpressionAt;
import gov.nasa.jpl.aerie.constraints.tree.ProfileExpression;
import gov.nasa.jpl.aerie.constraints.tree.RealValue;
import gov.nasa.jpl.aerie.constraints.tree.StructExpressionAt;
import gov.nasa.jpl.aerie.constraints.tree.ValueAt;
import gov.nasa.jpl.aerie.merlin.driver.OneStepTask;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.DurationType;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.scheduler.model.ActivityType;
import gov.nasa.jpl.aerie.scheduler.model.SchedulingActivity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.HOUR;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.MICROSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InstantiateArgumentsTest {
  private static final Duration oneMinute = Duration.of(1, Duration.MINUTES);

  /*
    This test exclusively looks at implementations of Expression that directly use the `bounds` parameter of evaluate()
    Specifically, it looks at:
    - StructExpressionAt
    - ListExpressionAt
    - ValueAt
    - DiscreteValue
    - RealValue
  */
  @Test
  public void instantiateArgumentsSingletonBoundsTest(){
    // Environment needs data
    final ActivityInstance activityInstance = new ActivityInstance(0, "Faux", Map.of("PeelCount", SerializedValue.of(1)), Interval.between(Duration.of(1, HOUR), Duration.of(2, HOUR)));

    final EvaluationEnvironment environment = new EvaluationEnvironment(
        Map.of("Faux", activityInstance),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
    final SimulationResults simulationResults = new SimulationResults(
        Instant.EPOCH,
        Interval.FOREVER,
        List.of(activityInstance),
        Map.of(),
        Map.of());
    final ActivityType fauxType = new ActivityType("Faux", delayedActivityDirective, DurationType.uncontrollable());

    final StructExpressionAt sea = new StructExpressionAt(
        Map.ofEntries(
            Map.entry("variant", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("option2")))),
            Map.entry("struct", new ProfileExpression<>(new StructExpressionAt(
                Map.ofEntries(
                    Map.entry("subfield", new ProfileExpression<>(new DiscreteValue(SerializedValue.of("value1")))),
                    Map.entry("subList", new ProfileExpression<>(
                        new ListExpressionAt(
                            List.of(
                                new ProfileExpression<>(new StructExpressionAt(
                                    Map.of("subListSubStruct", new ProfileExpression<>(new DiscreteValue(SerializedValue.of(2)))))))))))
                  ))),
            Map.entry("valueAt", new ProfileExpression<>(new ValueAt<>(
                new ProfileExpression<>(new RealValue(20)),
                new ActivitySpan("Faux")))),
            Map.entry("duration", new ProfileExpression<>(new DiscreteValue(SerializedValue.of(Duration.of(1, HOUR).in(MICROSECONDS))))))
    );

    final var arguments = SchedulingActivity.instantiateArguments(sea.fields(), Duration.of(1, HOUR), simulationResults, environment, fauxType);

    assertEquals(4, arguments.size());

    assertTrue(arguments.containsKey("variant"));
    assertTrue(arguments.containsKey("struct"));
    assertTrue(arguments.containsKey("valueAt"));
    assertTrue(arguments.containsKey("duration"));

    // Variant
    assertTrue(arguments.get("variant").asString().isPresent());
    assertEquals("option2", arguments.get("variant").asString().get());

    // Struct
    assertTrue(arguments.get("struct").asMap().isPresent());
    final var structMap = arguments.get("struct").asMap().get();
    assertEquals(2, structMap.size());
    // Subfield
    assertTrue(structMap.containsKey("subfield"));
    assertTrue(structMap.get("subfield").asString().isPresent());
    assertEquals("value1", structMap.get("subfield").asString().get());
    // Sublist
    assertTrue(structMap.containsKey("subList"));
    assertTrue(structMap.get("subList").asList().isPresent());
    final var subList = structMap.get("subList").asList().get();
    assertEquals(1, subList.size());
    assertTrue(subList.get(0).asMap().isPresent());
    final var subListSubStruct = subList.get(0).asMap().get();
    assertEquals(1, subListSubStruct.size());
    assertTrue(subListSubStruct.containsKey("subListSubStruct"));
    assertTrue(subListSubStruct.get("subListSubStruct").asInt().isPresent());
    assertEquals(2, subListSubStruct.get("subListSubStruct").asInt().get());

    // ValueAt
    assertTrue(arguments.get("valueAt").asReal().isPresent());
    assertEquals(20, arguments.get("valueAt").asReal().get());

    // Duration
    assertTrue(arguments.get("duration").asInt().isPresent());
    assertEquals(Duration.of(1, HOUR).in(MICROSECONDS), arguments.get("duration").asInt().get());
  }

  private static final Topic<Object> delayedActivityDirectiveInputTopic = new Topic<>();
  private static final Topic<Object> delayedActivityDirectiveOutputTopic = new Topic<>();
  private static final DirectiveType<Object, Object, Object> delayedActivityDirective = new DirectiveType<>() {
    @Override
    public InputType<Object> getInputType() {
      return testModelInputType;
    }

    @Override
    public OutputType<Object> getOutputType() {
      return testModelOutputType;
    }

    @Override
    public Optional<String> getSubsystem() {
      return Optional.empty();
    }

    @Override
    public TaskFactory<Object> getTaskFactory(final Object o, final Object o2) {
      return executor -> new OneStepTask<>($ -> {
        $.emit(this, delayedActivityDirectiveInputTopic);
        return TaskStatus.delayed(oneMinute, new OneStepTask<>($$ -> {
          $$.emit(Unit.UNIT, delayedActivityDirectiveOutputTopic);
          return TaskStatus.completed(Unit.UNIT);
        }));
      });
    }
  };

  private static final InputType<Object> testModelInputType = new InputType<>() {
    @Override
    public List<Parameter> getParameters() {
      return List.of(
          new Parameter("variant", ValueSchema.ofVariant(List.of(new ValueSchema.Variant("option2", "2")))),
          new Parameter("struct", ValueSchema.ofStruct(
              Map.of("subfield", ValueSchema.STRING,
                     "subList", ValueSchema.ofSeries(ValueSchema.ofStruct(Map.of("subListSubStruct", ValueSchema.INT))))
          )),
          new Parameter("valueAt", ValueSchema.REAL),
          new Parameter("duration", ValueSchema.DURATION));
    }

    @Override
    public List<String> getRequiredParameters() {
      return List.of();
    }

    @Override
    public Object instantiate(final Map arguments) {
      return new Object();
    }

    @Override
    public Map<String, SerializedValue> getArguments(final Object value) {
      return Map.of();
    }

    @Override
    public List<ValidationNotice> getValidationFailures(final Object value) {
      return List.of();
    }
  };
  private static final OutputType<Object> testModelOutputType = new OutputType<>() {
    @Override
    public ValueSchema getSchema() {
      return ValueSchema.ofStruct(Map.of());
    }

    @Override
    public SerializedValue serialize(final Object value) {
      return SerializedValue.of(Map.of());
    }
  };
}
