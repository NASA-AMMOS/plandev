package gov.nasa.jpl.plandev.merlin.server.remotes.postgres;

import java.nio.file.Path;

public record MissionModelRecord(
    String mission,
    String name,
    String version,
    String owner,
    Path path) {}
