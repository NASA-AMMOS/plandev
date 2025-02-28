package gov.nasa.jpl.aerie.merlin.worker.postgres;

import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfiles;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.DatabaseException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class PostgresProfileStreamer implements Consumer<ResourceProfiles>, AutoCloseable {
  private final ExecutorService queryQueue;
  private final PostgresProfileQueryHandler queryHandler;

  public PostgresProfileStreamer(DataSource dataSource, long datasetId) throws SQLException {
    this.queryQueue = Executors.newSingleThreadExecutor();
    this.queryHandler = new PostgresProfileQueryHandler(dataSource, datasetId);
  }

  @Override
  public void accept(final ResourceProfiles resourceProfiles) {
    queryQueue.submit(() -> {
      queryHandler.uploadResourceProfiles(resourceProfiles);
    });
  }

  @Override
  public void close() {
    queryQueue.shutdown();
      try {
          queryHandler.close();
      } catch (SQLException e) {
          throw new DatabaseException("Error occurred while attempting to close PostgresProfileQueryHandler", e);
      }
  }

}
