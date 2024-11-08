package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.server.remotes.ExternalEventsRepository;
import gov.nasa.jpl.aerie.merlin.server.remotes.MissionModelRepository;
import gov.nasa.jpl.aerie.merlin.server.services.ExternalEventsService;

import javax.json.JsonObject;
import javax.sql.DataSource;
import java.sql.SQLException;

public class PostgresExternalEventsRepository implements ExternalEventsRepository {
  private final DataSource dataSource;

  public PostgresExternalEventsRepository(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  private String convertJsonbToString(String rawJsonB) {
    return rawJsonB.substring(1, rawJsonB.length()-1)
                   .replace("\\\"","\"")
                   .replace("\\n","\n");
  }

  @Override
  public String getExternalSourceSchemaJsonb(final String sourceType) throws ExternalEventsService.NoSuchExternalSourceTypeException {
    try (final var connection = this.dataSource.getConnection()) {
      try (final var getExternalSourceSchemaJsonbAction = new GetExternalSourceSchemaJsonbAction(connection)) {
        var results = getExternalSourceSchemaJsonbAction.get(sourceType);
        var jsonbRaw = results.getFirst(); // investigate
        return convertJsonbToString(jsonbRaw);
      }
    } catch (final SQLException ex) {
      throw new ExternalEventsService.NoSuchExternalSourceTypeException("Failed to retrieve external source type with name `%s`".formatted(sourceType), ex);
    }
  }
}
