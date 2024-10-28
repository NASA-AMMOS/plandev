package gov.nasa.jpl.aerie.command_expansion.expansion;

import gov.nasa.jpl.aerie.command_expansion.command_activities.Command;
import gov.nasa.jpl.aerie.command_expansion.command_activities.Generic_Command;
import gov.nasa.jpl.aerie.command_expansion.generated.ActivityTypes;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.ActivityMapper;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InstantiationException;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.*;
import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.SeqJsonCommandArg.*;
import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.SeqJsonStepTime.*;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;

// Mission-specific "logical" sequence. In this case, it's specifically for this mission, and based on Seq.JSON / FCPL
/**
 * Sequence as understood by this mission.
 */
public record Sequence(
        String seqId,
        List<TimedCommand> commands) {

    public SeqJsonSequence toSeqJson() {
        return new SeqJsonSequence(
                seqId,
                commands.stream()
                        .map(cmd -> SeqJsonStep.command(
                                switch (cmd.timeTag()) {
                                    case TimedCommand.AbsoluteTimeTag absolute -> SeqJsonStepTime.absolute(absolute.time());
                                    case TimedCommand.RelativeTimeTag relative -> SeqJsonStepTime.relative(relative.offset());
                                    case TimedCommand.EpochRelativeTimeTag epochRelative -> SeqJsonStepTime.epochRelative(epochRelative.offset());
                                    case TimedCommand.CommandCompleteTimeTag commandComplete -> SeqJsonStepTime.commandComplete();
                                },
                                cmd.command().stem(),
                                cmd.command().args().stream().map(SeqJsonCommandArg::of).toList()
                        ))
                        .toList()
        );
    }

    public static Sequence parse(SeqJsonSequence sequence) {
        var commands = new ArrayList<TimedCommand>();
        for (var step : sequence.steps()) {
            switch (step.type()) {
                case "command":
                    commands.add(new TimedCommand(
                            getTimeTag(step.time()),
                            getCommand(step.stem(), step.args())));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported step type: " + step.type());
            }
        }
        return new Sequence(sequence.id(), commands);
    }

    private static TimedCommand.TimeTag getTimeTag(SeqJsonStepTime time) {
        return switch (time.type()) {
            case ABSOLUTE_TIME_TYPE -> new TimedCommand.AbsoluteTimeTag((Instant) time.tag());
            case RELATIVE_TIME_TYPE -> new TimedCommand.RelativeTimeTag((Duration) time.tag());
            case EPOCH_RELATIVE_TIME_TYPE -> new TimedCommand.EpochRelativeTimeTag((Duration) time.tag());
            case COMMAND_COMPLETE_TIME_TYPE -> new TimedCommand.CommandCompleteTimeTag();
            default -> throw new IllegalArgumentException("Unsupported time type: " + time.type());
        };
    }

    private static Command getCommand(String stem, List<SeqJsonCommandArg> args) {
        ActivityMapper<Mission, ?, ?> activityMapper = ActivityTypes.directiveTypes.get(stem);
        if (activityMapper == null) {
            LOGGER.warning("%s is not a modeled command stem, using generic command model instead.", stem);
            var cmd = new Generic_Command();
            cmd.stem = stem;
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
