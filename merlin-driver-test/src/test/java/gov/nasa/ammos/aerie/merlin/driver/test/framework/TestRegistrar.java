package gov.nasa.ammos.aerie.merlin.driver.test.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.ModelType;
import gov.nasa.jpl.aerie.merlin.protocol.model.OutputType;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TestRegistrar {
  List<Pair<String, Consumer<String>>> activities = new ArrayList<>();
  List<Cell> cells = new ArrayList<>();
  List<Runnable> daemons = new ArrayList<>();
  List<Pair<String, Supplier<?>>> resources = new ArrayList<>();

  public <T> Cell cell() {
    final Cell cell = Cell.of();
    cells.add(cell);
    return cell;
  }

  public <T> Cell linearCell() {
    final Cell cell = Cell.ofLinear();
    cells.add(cell);
    return cell;
  }

  public void activity(String name, Consumer<String> effectModel) {
    this.activities.add(Pair.of(name, effectModel));
  }

  public void daemon(Runnable runnable) {
    this.daemons.add(runnable);
  }

  public <T> void resource(String name, Supplier<T> supplier) {
    this.resources.add(Pair.of(name, supplier));
  }

  public static final class CellMap {

    private final Map<Cell, CellId<?>> cells = new LinkedHashMap<>();
    public <T> void put(Cell cell, CellId<MutableObject<T>> cellId) {
      cells.put(Objects.requireNonNull(cell), Objects.requireNonNull(cellId));
    }
    @SuppressWarnings("unchecked")
    public <T> CellId<T> get(Cell cell) {
      return (CellId<T>) Objects.requireNonNull(cells.get(Objects.requireNonNull(cell)));
    }

  }
  /**
   * Produce a simulatable ModeLType. The two values are the config and the model itself. Using a CellMap as the model\
   * object helps thread the CellMap through to where it's needed without the need for out-of-band communication.
   */
  public ModelType<Unit, CellMap> asModelType() {
    final var directives = new HashMap<String, DirectiveType<CellMap, Map<String, SerializedValue>, Unit>>();
    final var inputTopics = new HashMap<String, Topic<String>>();
    final var outputTopics = new HashMap<String, Topic<Unit>>();

    for (final var activity : activities) {
      final Topic<String> inputTopic = new Topic<>();
      final Topic<Unit> outputTopic = new Topic<>();
      inputTopics.put(activity.getLeft(), inputTopic);
      outputTopics.put(activity.getLeft(), outputTopic);
      directives.put(activity.getKey(), new DirectiveType<>() {

        @Override
        public InputType<Map<String, SerializedValue>> getInputType() {
          return new InputType<>() {
            @Override
            public List<Parameter> getParameters() {
              return List.of();
            }

            @Override
            public List<String> getRequiredParameters() {
              return List.of();
            }

            @Override
            public Map<String, SerializedValue> instantiate(final Map<String, SerializedValue> arguments) {
              return arguments;
            }

            @Override
            public Map<String, SerializedValue> getArguments(final Map<String, SerializedValue> value) {
              return Map.of();
            }

            @Override
            public List<ValidationNotice> getValidationFailures(final Map<String, SerializedValue> value) {
              return List.of();
            }
          };
        }

        @Override
        public OutputType<Unit> getOutputType() {
          return stubOutputType();
        }

        @Override
        public TaskFactory<Unit> getTaskFactory(final CellMap cellMap, final Map<String, SerializedValue> args) {
          return executor -> ThreadedTask.of(executor, cellMap, () -> {
            final SerializedValue value = args.get("value");
            final String input = value == null ? "" : value.asString().get();
            TestContext.get().scheduler().startActivity(input, inputTopic);
            activity.getValue().accept(input);
            TestContext.get().scheduler().endActivity(Unit.UNIT, outputTopic);
            return Unit.UNIT;
          });
        }
      });
    }

    return new ModelType<>() {
      @Override
      public Map<String, ? extends DirectiveType<CellMap, ?, ?>> getDirectiveTypes() {
        return directives;
      }

      @Override
      public InputType<Unit> getConfigurationType() {
        return new InputType<>() {
          @Override
          public List<Parameter> getParameters() {
            return List.of();
          }

          @Override
          public List<String> getRequiredParameters() {
            return List.of();
          }

          @Override
          public Unit instantiate(final Map<String, SerializedValue> arguments) {
            return Unit.UNIT;
          }

          @Override
          public Map<String, SerializedValue> getArguments(final Unit value) {
            return Map.of();
          }

          @Override
          public List<ValidationNotice> getValidationFailures(final Unit value) {
            return List.of();
          }
        };
      }

      @Override
      public CellMap instantiate(
          final Instant planStart,
          final Unit configuration,
          final Initializer builder)
      {
        for (final var directive : directives.entrySet()) {
          builder.topic(
              "ActivityType.Input." + directive.getKey(),
              inputTopics.get(directive.getKey()),
              new OutputType<String>() {
                @Override
                public ValueSchema getSchema() {
                  return ValueSchema.ofStruct(Map.of("value", ValueSchema.STRING));
                }

                @Override
                public SerializedValue serialize(final String value) {
                  return SerializedValue.of(Map.of("value", SerializedValue.of(value)));
                }
              });
          builder.topic(
              "ActivityType.Output." + directive.getKey(),
              outputTopics.get(directive.getKey()),
              stubOutputType());
        }
        final var cellMap = new CellMap();
        for (final var cell : cells) {
          if (cell.isLinear()) {
            cellMap.put(cell, LinearDynamics.allocate(builder, cell.linearTopic()));
          } else {
            cellMap.put(cell, History.allocate(builder, cell.topic()));
          }
        }
        for (final var daemon : daemons) {
          builder.daemon(executor -> ThreadedTask.of(executor, cellMap, () -> {daemon.run(); return Unit.UNIT;}));
        }
        for (final var resource : resources) {
          builder.resource(resource.getLeft(), new Resource<Object>() {
            @Override
            public String getType() {
              return "discrete";
            }

            @Override
            public OutputType<Object> getOutputType() {
              return new OutputType<>() {
                @Override
                public ValueSchema getSchema() {
                  return ValueSchema.ofStruct(Map.of());
                }

                @Override
                public SerializedValue serialize(final Object value) {
                  return SerializedValue.of(value.toString());
                }
              };
            }

            @Override
            public Object getDynamics(final Querier querier) {
              return TestContext.set(
                  new TestContext.Context(cellMap, schedulerOfQuerier(querier), null),
                  resource.getRight()::get);
            }
          });
        }
        return cellMap;
      }
    };
  }

  public static Scheduler schedulerOfQuerier(Querier querier) {
    return new Scheduler() {
      @Override
      public <State> State get(final CellId<State> cellId) {
        return querier.getState(cellId);
      }

      @Override
      public <Event> void emit(final Event event, final Topic<Event> topic) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void spawn(final InSpan taskSpan, final TaskFactory<?> task) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> void startActivity(final T activity, final Topic<T> inputTopic) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> void endActivity(final T result, final Topic<T> outputTopic) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <ActivityDirectiveId> void startDirective(
          final ActivityDirectiveId activityDirectiveId,
          final Topic<ActivityDirectiveId> activityTopic)
      {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static <T> OutputType<T> stubOutputType() {
    return new OutputType<>() {
      @Override
      public ValueSchema getSchema() {
        return ValueSchema.ofStruct(Map.of());
      }

      @Override
      public SerializedValue serialize(final T value) {
        return SerializedValue.of(Map.of());
      }
    };
  }
}
