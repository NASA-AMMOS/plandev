package gov.nasa.jpl.aerie.contrib.models;

public record ValidationResult(boolean success, String subject, String message) {}
