package gov.nasa.jpl.aerie.command_expansion.expansion;

import gov.nasa.jpl.aerie.command_expansion.command_activities.Command;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.*;
import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.SeqJsonStepTime.*;

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
                            getCommand(step.stem(), step.args())))
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported step type: " + step.type());
            }
        }
        return null;
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
        // TODO - this is the key method for "parse" / "run generic sequence" capabilities.
        // Current idea:
        //   First, we need the annotation parser to generate a mapping from activity names to mappers,
        //   possibly with additional information about package name or additional metadata.
        //     The intention with additional metadata is to distinguish command activities from other kinds,
        //     either by putting commands in a specific package like we have here, or by tagging them somehow.
        //   Second, we can use the getParameters() method to turn the positional args into named args,
        //   convert the values to the appropriate types, and generate the SerializedValue representation of the command.
        //   Finally, we can call the mapper's instantiate method to build the activity.
        throw new UnsupportedOperationException("Not implemented");
    }
}
