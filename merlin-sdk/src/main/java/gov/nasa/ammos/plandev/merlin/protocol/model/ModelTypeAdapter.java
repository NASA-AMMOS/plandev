package gov.nasa.ammos.plandev.merlin.protocol.model;

import gov.nasa.ammos.plandev.merlin.protocol.driver.Initializer;
import gov.nasa.ammos.plandev.merlin.protocol.driver.Scheduler;
import gov.nasa.ammos.plandev.merlin.protocol.types.InstantiationException;
import gov.nasa.ammos.plandev.merlin.protocol.types.SerializedValue;
import gov.nasa.ammos.plandev.merlin.protocol.types.TaskStatus;
import gov.nasa.ammos.plandev.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

public record ModelTypeAdapter<Config,
        Model>(gov.nasa.jpl.aerie.merlin.protocol.model.ModelType<Config, Model> plugin) implements ModelType<Config, Model> {

    @Override
    public Map<String, ? extends DirectiveType<Model, ?, ?>> getDirectiveTypes() {
        return plugin
                .getDirectiveTypes()
                .entrySet()
                .stream()
                .map((DirectiveType<T, K> $) -> new DirectiveType<>() {}).collect(Collectors.toMap($ -> $.getKey(),
                                                                                       $ -> $.getValue()));;
    }

    @Override
    public InputType<Config> getConfigurationType() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Model instantiate(Instant planStart, Config configuration, Initializer builder) {
        throw new UnsupportedOperationException();
    }

    private <Model, Arguments, Result> DirectiveType<Model, Arguments, Result> adaptDirectiveType(gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType<Model, Arguments, Result> directiveType) {
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
                var taskFactory = directiveType.getTaskFactory(model, arguments);
                return new TaskFactory<Result>() {
                    @Override
                    public Task<Result> create(Executor executor) {
                        var task = taskFactory.create(executor);
                        return new Task<Result>() {
                            @Override
                            public TaskStatus<Result> step(Scheduler scheduler) {
                                return task.step(scheduler);
                            }
                        }
                        return ;
                    }
                }
                return ;
            }
        };
    }

    private <Arguments> InputType<Arguments> adaptInputType(gov.nasa.jpl.aerie.merlin.protocol.model.InputType<Arguments> inputType) {
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
                        e.extraneousArguments,
                        e.unconstructableArguments,
                        e.missingArguments,
                        e.validArguments
                    );
                }
            }

            @Override
            public Map<String, SerializedValue> getArguments(Arguments value) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ValidationNotice> getValidationFailures(Arguments value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private <T> OutputType<T> adaptOutputType(gov.nasa.jpl.aerie.merlin.protocol.model.OutputType<T> outputType) {
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

    private InputType.Parameter adaptParameter(gov.nasa.jpl.aerie.merlin.protocol.model.InputType.Parameter parameter) {
        return new InputType.Parameter(parameter.name(), adaptValueSchema(parameter.schema()));
    }

    private ValueSchema adaptValueSchema(gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema schema) {
        switch (schema) {
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.BooleanSchema s -> new ValueSchema.BooleanSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.DurationSchema s -> new ValueSchema.DurationSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.IntSchema s -> new ValueSchema.IntSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.PathSchema s -> new ValueSchema.PathSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.RealSchema s -> new ValueSchema.RealSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.MetaSchema s -> new ValueSchema.MetaSchema(s.metadata(), adaptValueSchema(s.target()));
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.SeriesSchema s -> new ValueSchema.SeriesSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.StringSchema s -> new ValueSchema.StringSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.StructSchema s -> new ValueSchema.StructSchema();
            case gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema.VariantSchema s -> new ValueSchema.VariantSchema();
        }
    }

    private SerializedValue adaptSerializedValue(gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue serializedValue) {
        switch (serializedValue) {
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.BooleanValue v -> new SerializedValue.BooleanValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.DoubleValue v -> new SerializedValue.DoubleValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.NumericValue v -> new SerializedValue.NumericValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.StringValue v -> new SerializedValue.StringValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.ListValue v -> new SerializedValue.ListValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.MapValue v -> new SerializedValue.MapValue(v.value());
            case gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue.NullValue v -> new SerializedValue.NullValue(v.value());
        }
    }

    private gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler adaptScheduler(Scheduler scheduler) {
        return new gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler() {
            @Override
            public <State> State get(CellId<State> cellId) {
                return scheduler.get(cellId);
            }

            @Override
            public <Event> void emit(Event event, Topic<Event> topic) {

            }

            @Override
            public void spawn(InSpan taskSpan, gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory<?> task) {

            }
        }
    }
}
