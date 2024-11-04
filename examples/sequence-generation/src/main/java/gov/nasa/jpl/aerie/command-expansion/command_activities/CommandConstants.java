package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

public final class CommandConstants {
    private CommandConstants() {}

    public static final Duration SEQ_COMMAND_DURATION = Duration.of(1, SECOND);
}
