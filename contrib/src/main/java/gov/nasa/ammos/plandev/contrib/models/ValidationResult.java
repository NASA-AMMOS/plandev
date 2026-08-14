package gov.nasa.jpl.aerie.contrib.models;

import java.util.Optional;

public record ValidationResult(boolean success, String subject, String message) {}
