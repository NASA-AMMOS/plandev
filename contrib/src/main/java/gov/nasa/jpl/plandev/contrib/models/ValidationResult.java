package gov.nasa.jpl.plandev.contrib.models;

import java.util.Optional;

public record ValidationResult(boolean success, String subject, String message) {}
