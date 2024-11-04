package gov.nasa.jpl.aerie.command_expansion.util;

import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Optional;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentTime;

public final class TimeCondition {
    private TimeCondition() {}

    /**
     * Return a condition which is true at and after the given absolute time (as an offset from plan start).
     */
    public static Condition at(Duration absoluteSimTime) {
        return (positive, atEarliest, atLatest) -> {
            var relativeTargetTime = absoluteSimTime.minus(currentTime());
            if (positive) {
                return Optional.of(Duration.max(atEarliest, relativeTargetTime))
                        .filter(atLatest::noShorterThan);
            } else {
                return Optional.of(atEarliest).filter(relativeTargetTime::longerThan);
            }
        };
    }

    /**
     * Return a condition which is true at and after the given relative time from now.
     */
    public static Condition in(Duration relativeSimTime) {
        return at(currentTime().plus(relativeSimTime));
    }
}
