package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.server.remotes.ExternalEventsRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.MissionModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;

public class LocalExternalEventsService implements ExternalEventsService {
  private static final Logger log = LoggerFactory.getLogger(LocalMissionModelService.class);
  private final ExternalEventsRepository externalEventsRepository;
  public LocalExternalEventsService(
      final ExternalEventsRepository externalEventsRepository
  ) {
    this.externalEventsRepository = externalEventsRepository;
  }

  @Override
  public String getExternalSourceTypeSchema(final String sourceType) throws NoSuchExternalSourceTypeException {
    return externalEventsRepository.getExternalSourceSchemaJsonb(sourceType);
  }

  @Override
  public void uploadExternalSourceType(final String name, final String valueSchema)
  throws NoSuchExternalSourceTypeException
  {
    externalEventsRepository.uploadExternalSourceType(name, valueSchema);
  }
}
