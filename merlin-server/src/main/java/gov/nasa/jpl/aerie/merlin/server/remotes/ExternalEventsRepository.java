package gov.nasa.jpl.aerie.merlin.server.remotes;

import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.DatabaseException;
import gov.nasa.jpl.aerie.merlin.server.services.ExternalEventsService;

public interface ExternalEventsRepository {
  public String getExternalSourceSchemaJsonb(String sourceType) throws
                                                                ExternalEventsService.NoSuchExternalSourceTypeException;
}
