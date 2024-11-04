package gov.nasa.jpl.aerie.command_expansion.expansion;

import gov.nasa.jpl.aerie.command_expansion.command_activities.Command;
import gov.nasa.jpl.aerie.command_expansion.ground_events.GroundEvent;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.time.Instant;

import static gov.nasa.jpl.aerie.command_expansion.expansion.SeqJsonSequence.*;

public record TimedStep(TimeTag timeTag, SequenceStep step) {
    public sealed interface TimeTag { }
    public record AbsoluteTimeTag(Instant time) implements TimeTag { }
    public record RelativeTimeTag(Duration offset) implements TimeTag { }
    public record EpochRelativeTimeTag(Duration offset) implements TimeTag { }
    public record CommandCompleteTimeTag() implements TimeTag { }

    public static TimedStep absolute(final Instant time, final SequenceStep step) {
        return new TimedStep(new AbsoluteTimeTag(time), step);
    }

    public static TimedStep relative(final Duration offset, final SequenceStep step) {
        return new TimedStep(new RelativeTimeTag(offset), step);
    }

    public static TimedStep epochRelative(final Duration offset, final SequenceStep step) {
        return new TimedStep(new EpochRelativeTimeTag(offset), step);
    }

    public static TimedStep commandComplete(final SequenceStep step) {
        return new TimedStep(new CommandCompleteTimeTag(), step);
    }

    public SeqJsonStep toSeqJson() {
        var time = switch (timeTag()) {
            case AbsoluteTimeTag absolute -> new SeqJsonAbsoluteTime(absolute.time());
            case RelativeTimeTag relative -> new SeqJsonRelativeTime(relative.offset());
            case EpochRelativeTimeTag epochRelative -> new SeqJsonEpochRelativeTime(epochRelative.offset());
            case CommandCompleteTimeTag commandComplete -> new SeqJsonCommandCompleteTime();
        };

        return switch (step) {
            case Command command -> new SeqJsonCommand(
                    time,
                    command.stem(),
                    command.args().stream().map(SeqJsonCommandArg::of).toList());
            case GroundEvent groundEvent -> new SeqJsonGroundEvent(
                    time,
                    groundEvent.name(),
                    groundEvent.args().stream().map(SeqJsonCommandArg::of).toList());
            default -> throw new IllegalStateException("Unknown step type: " + step.getClass().getSimpleName());
        };
    }
}
