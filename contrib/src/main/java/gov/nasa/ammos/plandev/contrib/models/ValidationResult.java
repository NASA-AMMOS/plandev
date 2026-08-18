package gov.nasa.ammos.plandev.contrib.models;

public record ValidationResult(boolean success, String subject, String message) {}
