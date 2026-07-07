package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.ammos.plandev.merlin.protocol.driver.Initializer;
import gov.nasa.ammos.plandev.merlin.protocol.driver.Querier;
import gov.nasa.ammos.plandev.merlin.protocol.driver.Scheduler;
import gov.nasa.ammos.plandev.merlin.protocol.model.Condition;
import gov.nasa.ammos.plandev.merlin.protocol.model.DirectiveType;
import gov.nasa.ammos.plandev.merlin.protocol.model.EffectTrait;
import gov.nasa.ammos.plandev.merlin.protocol.model.InputType;
import gov.nasa.ammos.plandev.merlin.protocol.model.ModelType;
import gov.nasa.ammos.plandev.merlin.protocol.model.OutputType;
import gov.nasa.ammos.plandev.merlin.protocol.model.Task;
import gov.nasa.ammos.plandev.merlin.protocol.model.TaskFactory;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.TaskStatus;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.ammos.plandev.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.Resource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public record ModelTypeAdapter<Config,
        Model>(gov.nasa.jpl.aerie.merlin.protocol.model.ModelType<Config, Model> modelType) implements ModelType<Config, Model> {

    @Override
    public Map<String, ? extends DirectiveType<Model, ?, ?>> getDirectiveTypes() {
        var newDirectiveTypes = new LinkedHashMap<String, DirectiveType<Model, ?, ?>>();
        for (var entry : modelType.getDirectiveTypes().entrySet()) {
            newDirectiveTypes.put(entry.getKey(), adaptDirectiveType(entry.getValue()));
        }
        return newDirectiveTypes;
    }

    @Override
    public InputType<Config> getConfigurationType() {
        return adaptInputType(modelType.getConfigurationType());
    }

    @Override
    public Model instantiate(Instant planStart, Config configuration, Initializer builder) {
        return modelType.instantiate(
                planStart,
                configuration,
                new gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer() {
                    @Override
                    public <State> State getInitialState(CellId<State> cellId) {
                        return builder.getInitialState(cellId);
                    }

                    @Override
                    public <Event, Effect, State> CellId<State> allocate(
                            State initialState,
                            CellType<Effect, State> cellType,
                            Function<Event, Effect> interpretation,
                            Topic<Event> topic) {
                        return (CellId<State>) builder.allocate(initialState, adaptCellType(cellType), interpretation, topic.topic);
                    }

                    @Override
                    public void daemon(gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory<?> factory) {
                        builder.daemon(adaptTaskFactory(factory));
                    }

                    @Override
                    public void resource(String name, Resource<?> resource) {
                        builder.resource(name, adaptResource(resource));
                    }

                    @Override
                    public <Event> void topic(
                            String name,
                            Topic<Event> topic,
                            gov.nasa.jpl.aerie.merlin.protocol.model.OutputType<Event> outputType) {

                    }
                });
    }

    private <T> gov.nasa.ammos.plandev.merlin.protocol.model.Resource<T> adaptResource(Resource<T> resource) {
        return new gov.nasa.ammos.plandev.merlin.protocol.model.Resource<T>() {
            @Override
            public String getType() {
                return resource.getType();
            }

            @Override
            public OutputType<T> getOutputType() {
                return adaptOutputType(resource.getOutputType());
            }

            @Override
            public T getDynamics(Querier querier) {
                return resource.getDynamics(querier::getState);
            }
        };
    }

    private static <Model, Arguments, Result> DirectiveType<Model, Arguments, Result> adaptDirectiveType(gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType<Model, Arguments, Result> directiveType) {
        return new DirectiveType<Model, Arguments, Result>() {
            @Override
            public InputType<Arguments> getInputType() {
                return adaptInputType(directiveType.getInputType());
            }

            @Override
            public OutputType<Result> getOutputType() {
                return adaptOutputType(directiveType.getOutputType());
            }

            @Override
            public TaskFactory<Result> getTaskFactory(Model model, Arguments arguments) {
                return adaptTaskFactory(directiveType.getTaskFactory(model, arguments));
            }
        };
    }

    private static <Result> TaskFactory<Result> adaptTaskFactory(gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory<Result> taskFactory) {
        return executor -> {
            var task = taskFactory.create(executor);
            return (Task<Result>) scheduler -> adaptTaskStatus(task.step(adaptScheduler(scheduler)));
        };
    }

    private static <Result> Task<Result> adaptTask(gov.nasa.jpl.aerie.merlin.protocol.model.Task task) {
        return scheduler -> adaptTaskStatus(task.step(adaptScheduler(scheduler)));
    }

    private static <Result> TaskStatus<Result> adaptTaskStatus(gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus<Result> status) {
        return switch (status) {
            case gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus.AwaitingCondition<Result> v -> new TaskStatus.AwaitingCondition<>(adaptCondition(v.condition()), adaptTask(v.continuation()));
            case gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus.CallingTask<Result> v -> new TaskStatus.CallingTask<>(adaptInSpan(v.childSpan()), adaptTaskFactory(v.child()), adaptTask(v.continuation()));
            case gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus.Completed<Result> v -> new TaskStatus.Completed<>(v.returnValue());
            case gov.nasa.jpl.aerie.merlin.protocol.types.TaskStatus.Delayed<Result> v -> new TaskStatus.Delayed<>(adaptDuration(v.delay()), adaptTask(v.continuation()));
        };
    }

    private static Condition adaptCondition(gov.nasa.jpl.aerie.merlin.protocol.model.Condition condition) {
        return (now, atLatest) -> condition.nextSatisfied(now::getState, adaptDuration(atLatest)).map(ModelTypeAdapter::adaptDuration);
    }

    private static <Arguments> InputType<Arguments> adaptInputType(gov.nasa.jpl.aerie.merlin.protocol.model.InputType<Arguments> inputType) {
        return new InputType<Arguments>() {
            @Override
            public List<Parameter> getParameters() {
                var result = new ArrayList<Parameter>();
                for (var parameter : inputType.getParameters()) {
                    result.add(adaptParameter(parameter));
                }
                return result;
            }

            @Override
            public List<String> getRequiredParameters() {
                return inputType.getRequiredParameters();
            }

            @Override
            public Arguments instantiate(Map<String, SerializedValue> arguments) throws InstantiationException {
                var newArguments = new HashMap<String, gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue>();
                try {
                    return inputType.instantiate(newArguments);
                } catch (gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException e) {
                    throw new InstantiationException(
                            e.containerName,
                            e.extraneousArguments.stream().map($ -> new InstantiationException.ExtraneousArgument($.parameterName())).toList(),
                            e.unconstructableArguments.stream().map($ -> new InstantiationException.UnconstructableArgument($.parameterName(), $.failure())).toList(),
                            e.missingArguments.stream().map($ -> new InstantiationException.MissingArgument($.parameterName(), adaptValueSchema($.schema()))).toList(),
                            e.validArguments.stream().map($ -> new InstantiationException.ValidArgument($.parameterName(), adaptSerializedValue($.serializedValue()))).toList(),
                            e
                    );
                }
            }

            @Override
            public Map<String, SerializedValue> getArguments(Arguments value) {
                var newArguments = new LinkedHashMap<String, SerializedValue>();
                for (var entry : inputType.getArguments(value).entrySet()) {
                    newArguments.put(entry.getKey(), adaptSerializedValue(entry.getValue()));
                }
                return newArguments;
            }

            @Override
            public List<ValidationNotice> getValidationFailures(Arguments value) {
                var validationFailures = new ArrayList<ValidationNotice>();
                for (var validationFailure : inputType.getValidationFailures(value)) {
                    validationFailures.add(new ValidationNotice(validationFailure.subjects(), validationFailure.message()));
                }
                return validationFailures;
            }
        };
    }

    private static <T> OutputType<T> adaptOutputType(gov.nasa.jpl.aerie.merlin.protocol.model.OutputType<T> outputType) {
        return new OutputType<T>() {
            @Override
            public ValueSchema getSchema() {
                return adaptValueSchema(outputType.getSchema());
            }

            @Override
            public SerializedValue serialize(T value) {
                return adaptSerializedValue(outputType.serialize(value));
            }
        };
    }

    private static InputType.Parameter adaptParameter(gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter parameter) {
        return new InputType.Parameter(parameter.name(), adaptValueSchema(parameter.schema()));
    }

    private static ValueSchema adaptValueSchema(gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema schema) {
        return switch (schema) {
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.BooleanSchema s -> new ValueSchema.BooleanSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.DurationSchema s -> new ValueSchema.DurationSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.IntSchema s -> new ValueSchema.IntSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.PathSchema s -> new ValueSchema.PathSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.RealSchema s -> new ValueSchema.RealSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.MetaSchema s -> {
                var newMetadata = new LinkedHashMap<String, SerializedValue>();
                for (var entry : s.metadata().entrySet()) {
                    newMetadata.put(entry.getKey(), adaptSerializedValue(entry.getValue()));
                }
                yield new ValueSchema.MetaSchema(newMetadata, adaptValueSchema(s.target()));
            }
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.SeriesSchema s -> new ValueSchema.SeriesSchema(adaptValueSchema(s.value()));
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.StringSchema s -> new ValueSchema.StringSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.StructSchema s -> {
                var newSchema = new LinkedHashMap<String, ValueSchema>();
                for (var entry : s.value().entrySet()) {
                    newSchema.put(entry.getKey(), adaptValueSchema(entry.getValue()));
                }
                yield new ValueSchema.StructSchema(newSchema);
            }
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.VariantSchema s ->
                    new ValueSchema.VariantSchema(
                            s.variants()
                             .stream()
                             .map(variant -> new ValueSchema.Variant(variant.key(), variant.label()))
                             .toList());
        };
    }

    private static SerializedValue adaptSerializedValue(gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue serializedValue) {
        return switch (serializedValue) {
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.NullValue v -> new SerializedValue.NullValue();
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.BooleanValue v -> new SerializedValue.BooleanValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.DoubleValue v -> new SerializedValue.DoubleValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.NumericValue v -> new SerializedValue.NumericValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.StringValue v -> new SerializedValue.StringValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.ListValue v -> new SerializedValue.ListValue(v.getValue().stream().map(ModelTypeAdapter::adaptSerializedValue).toList());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.MapValue v -> {
                var newMap = new LinkedHashMap<String, SerializedValue>();
                for (var entry : v.getValue().entrySet()) {
                    newMap.put(entry.getKey(), adaptSerializedValue(entry.getValue()));
                }
                yield new SerializedValue.MapValue(newMap);
            }
        };
    }

    private static gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler adaptScheduler(Scheduler scheduler) {
        return new gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler() {
            @Override
            public <State> State get(CellId<State> cellId) {
                return scheduler.get(cellId);
            }

            @Override
            public <Event> void emit(Event event, Topic<Event> topic) {
                scheduler.emit(event, topic.topic);
            }

            @Override
            public void spawn(gov.nasa.jpl.aerie.merlin.protocol.types.InSpan taskSpan, gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory<?> task) {
                scheduler.spawn(adaptInSpan(taskSpan), adaptTaskFactory(task));
            }
        };
    }

    private static InSpan adaptInSpan(gov.nasa.jpl.aerie.merlin.protocol.types.InSpan inSpan) {
        return switch (inSpan) {
            case Parent -> gov.nasa.ammos.plandev.merlin.protocol.types.InSpan.Parent;
            case Fresh -> gov.nasa.ammos.plandev.merlin.protocol.types.InSpan.Fresh;
        };
    }

    private static Duration adaptDuration(gov.nasa.jpl.aerie.merlin.protocol.types.Duration d) {
        return new Duration(d.micros());
    }

    private static gov.nasa.jpl.aerie.merlin.protocol.types.Duration adaptDuration(Duration d) {
        return new gov.nasa.jpl.aerie.merlin.protocol.types.Duration(d.micros());
    }

    private static <Effect, State> gov.nasa.ammos.plandev.merlin.protocol.model.CellType<Effect, State> adaptCellType(CellType<Effect, State> cellType) {
        return new gov.nasa.ammos.plandev.merlin.protocol.model.CellType<Effect, State>() {
            @Override
            public EffectTrait<Effect> getEffectType() {
                var effectType = cellType.getEffectType();
                return new EffectTrait<Effect>() {
                    @Override
                    public Effect empty() {
                        return effectType.empty();
                    }

                    @Override
                    public Effect sequentially(Effect prefix, Effect suffix) {
                        return effectType.sequentially(prefix, suffix);
                    }

                    @Override
                    public Effect concurrently(Effect left, Effect right) {
                        return effectType.concurrently(left, right);
                    }
                };
            }

            @Override
            public State duplicate(State state) {
                return cellType.duplicate(state);
            }

            @Override
            public void apply(State state, Effect effect) {
                cellType.apply(state, effect);
            }
        };
    }
}
