package gov.nasa.jpl.aerie.scheduler.server.models;

import java.nio.file.Path;
import java.util.Optional;

public record GoalRecord(
    GoalId id,
    String name,
    GoalType type,
    boolean simulateAfter) {}
