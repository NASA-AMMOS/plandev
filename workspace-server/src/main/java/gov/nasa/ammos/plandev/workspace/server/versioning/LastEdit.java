package gov.nasa.ammos.plandev.workspace.server.versioning;

import java.time.Instant;

/** A file's most recent content change, derived from history. baseline marks the history's root commit. */
public record LastEdit(String by, Instant at, boolean baseline) {}
