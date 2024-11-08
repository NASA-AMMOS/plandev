package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.types.MissionModelId;

// TODO: MAKE LOCAL VERSION, MAKE STUB VERSION
public interface ExternalEventsService {
  // getExternalSources

  // getEventsByDerivationGroup

  // getExternalEventTypes

  // getExternalSourceTypes

  // get source type schema (as a string for now)
  String getExternalSourceTypeSchema(String sourceType) throws NoSuchExternalSourceTypeException;
  void uploadExternalSourceType(String name, String valueSchema) throws NoSuchExternalSourceTypeException;

  final class NoSuchExternalSourceTypeException extends Exception {
    public final String sourceType;

    public NoSuchExternalSourceTypeException(final String sourceType, final Throwable cause) {
      super("No external source type exists with id `" + sourceType + "`", cause);
      this.sourceType = sourceType;

    }

    public NoSuchExternalSourceTypeException(final String sourceType) { this(sourceType, null); }
  }
}
