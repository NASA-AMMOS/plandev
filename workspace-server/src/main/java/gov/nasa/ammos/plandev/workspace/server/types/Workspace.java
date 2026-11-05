package gov.nasa.ammos.plandev.workspace.server.types;

import java.nio.file.Path;

public record Workspace(int id, Path rootPath) {}
