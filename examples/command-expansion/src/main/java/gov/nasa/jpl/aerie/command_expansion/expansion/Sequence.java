package gov.nasa.jpl.aerie.command_expansion.expansion;

import gov.nasa.jpl.aerie.command_expansion.command_activities.Command;
import gov.nasa.jpl.aerie.command_expansion.command_activities.Generic_Command;
import gov.nasa.jpl.aerie.command_expansion.generated.ActivityTypes;
import gov.nasa.jpl.aerie.command_expansion.ground_events.GenericGroundEvent;
import gov.nasa.jpl.aerie.command_expansion.ground_events.GroundEvent;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.ActivityMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.*;
import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.SeqJsonCommandArg.*;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;

// Mission-specific "logical" sequence. In this case, it's specifically for this mission, and based on Seq.JSON / FCPL
/**
 * Sequence as understood by this mission.
 */
public record Sequence(
        String seqId,
        List<TimedStep> commands) {

    public SeqJsonSequence toSeqJson() {
        return new SeqJsonSequence(
                seqId,
                commands.stream().map(TimedStep::toSeqJson).toList());
    }

    public static Sequence parse(SeqJsonSequence sequence) {
        var steps = new ArrayList<TimedStep>();
        for (var seqJsonStep : sequence.steps()) {
            switch (seqJsonStep) {
                case SeqJsonCommand seqJsonCommand:
                    steps.add(new TimedStep(
                            getTimeTag(seqJsonCommand.time()),
                            getCommand(seqJsonCommand.stem(), seqJsonCommand.args())));
                    break;

                case SeqJsonGroundEvent seqJsonGroundEvent:
                    steps.add(new TimedStep(
                            getTimeTag(seqJsonGroundEvent.time()),
                            getGroundEvent(seqJsonGroundEvent.name(), seqJsonGroundEvent.args())));
                    break;

                default:
                    throw new IllegalArgumentException("Unsupported step type: " + seqJsonStep.getClass().getSimpleName());
            }
        }
        return new Sequence(sequence.id(), steps);
    }

    private static TimedStep.TimeTag getTimeTag(SeqJsonStepTime time) {
        return switch (time) {
            case SeqJsonAbsoluteTime absoluteTime -> new TimedStep.AbsoluteTimeTag(absoluteTime.tag());
            case SeqJsonCommandCompleteTime commandCompleteTime -> new TimedStep.CommandCompleteTimeTag();
            case SeqJsonEpochRelativeTime epochRelativeTime -> new TimedStep.EpochRelativeTimeTag(epochRelativeTime.tag());
            case SeqJsonRelativeTime relativeTime -> new TimedStep.RelativeTimeTag(relativeTime.tag());
        };
    }

    private static Command getCommand(String stem, List<SeqJsonCommandArg> args) {
        SequenceStep deepStep = getSequenceStep(stem, args);
        if (deepStep != null) {
            if (deepStep instanceof Command command) {
                return command;
            } else {
                // This is an odd corner case, where we recognize the step name, but the type is wrong.
                // Since the SEQ JSON clearly identifies each step type, we'll respect that.
                // That is, if you have both a ground event and a command with the same name,
                // and decide to model *only* the ground event (Don't do that. That's gross.),
                // then we'll run a generic unmodeled command, respecting the meaning of the sequence.

                // However, it's more likely that you meant to run the ground event or whatever that you actually modeled,
                // and just messed up the step type. For that reason, we'll suggest that step type to you in the log.
                LOGGER.warning("%s is not a modeled command stem. (Did you mean the %s?)",
                        stem, getStepType(deepStep).getSimpleName());
            }
        }

        LOGGER.warning("%s is not a modeled command stem, using generic command model instead.", stem);
        var cmd = new Generic_Command();
        cmd.stem = stem;
        cmd.args = args.stream().map(arg -> arg.value().toString()).toList();
        return cmd;
    }

    private static GroundEvent getGroundEvent(String name, List<SeqJsonCommandArg> args) {
        SequenceStep deepStep = getSequenceStep(name, args);
        if (deepStep != null) {
            if (deepStep instanceof GroundEvent groundEvent) {
                return groundEvent;
            } else {
                LOGGER.warning("%s is not a modeled ground event. (Did you mean the %s?)",
                        name, getStepType(deepStep).getSimpleName());
            }
        }

        LOGGER.warning("%s is not a modeled ground event, using generic ground event model instead.", name);
        var groundEvent = new GenericGroundEvent();
        groundEvent.name = name;
        groundEvent.args = args.stream().map(arg -> arg.value().toString()).toList();
        return groundEvent;
    }

    private static Class<? extends SequenceStep> getStepType(SequenceStep step) {
        return switch (step) {
            case Command command -> Command.class;
            case GroundEvent groundEvent -> GroundEvent.class;
            default -> throw new IllegalStateException("Incomplete switch statement for step type " + step.getClass().getSimpleName());
        };
    }

    private static SequenceStep getSequenceStep(String name, List<SeqJsonCommandArg> args) {
        ActivityMapper<Mission, ?, ?> activityMapper = ActivityTypes.directiveTypes.get(name);
        if (activityMapper == null) {
            LOGGER.warning("%s is not a modeled command stem, using generic command model instead.", name);
            var cmd = new Generic_Command();
            cmd.stem = name;
            cmd.args = args.stream().map(arg -> arg.value().toString()).toList();
            return cmd;
        }

        // Otherwise, we can do some type checking and conversion:
        var params = activityMapper.getInputType().getParameters();
        if (params.size() != args.size()) {
            throw new IllegalArgumentException(String.format(
                    "Wrong number of arguments. Expected %d, actual %d",
                    activityMapper.getInputType().getParameters().size(),
                    args.size()));
        }

        // TODO - better error reporting when this fails
        Map<String, SerializedValue> activityArgs = new HashMap<>();
        for (int i = 0; i < args.size(); ++i) {
            var arg = args.get(i);
            var param = params.get(i);

            SerializedValue activityArg = param.schema().match(new ValueSchema.Visitor<>() {
                @Override
                public SerializedValue onReal() {
                    assertType(arg, NUMBER_TYPE);
                    return SerializedValue.of((double) arg.value());
                }

                @Override
                public SerializedValue onInt() {
                    assertType(arg, NUMBER_TYPE);
                    return SerializedValue.of((long) arg.value());
                }

                @Override
                public SerializedValue onBoolean() {
                    return unsupportedSchema("boolean");
                }

                @Override
                public SerializedValue onString() {
                    assertType(arg, STRING_TYPE);
                    return SerializedValue.of((String) arg.value());
                }

                @Override
                public SerializedValue onDuration() {
                    return unsupportedSchema("Duration");
                }

                @Override
                public SerializedValue onPath() {
                    return unsupportedSchema("Path");
                }

                @Override
                public SerializedValue onSeries(ValueSchema value) {
                    return unsupportedSchema("List");
                }

                @Override
                public SerializedValue onStruct(Map<String, ValueSchema> value) {
                    return unsupportedSchema("Map");
                }

                @Override
                public SerializedValue onVariant(List<ValueSchema.Variant> variants) {
                    return SerializedValue.of((String) arg.value());
                }

                @Override
                public SerializedValue onMeta(Map<String, SerializedValue> metadata, ValueSchema target) {
                    return unsupportedSchema("Meta");
                }
            });
            activityArgs.put(param.name(), activityArg);
        }

        try {
            return (Command) activityMapper.getInputType().instantiate(activityArgs);
        } catch (InstantiationException | ClassCastException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertType(SeqJsonCommandArg arg, String expectedType) {
        if (!arg.type().equals(expectedType)) {
            throw new IllegalArgumentException(String.format(
                    "Expected type %s, actual type %s",
                    expectedType, arg.type()));
        }
    }

    private static SerializedValue unsupportedSchema(String schemaType) {
        throw new IllegalArgumentException(String.format(
                "Unsupported schema type: %s. Please use only string, number, and enum parameters in command activities.",
                schemaType));
    }
}
