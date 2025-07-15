package gov.nasa.jpl.aerie.merlin.worker.postgres;

import gov.nasa.jpl.aerie.merlin.driver.resources.AsyncConsumer;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfiles;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.DatabaseException;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PostgresProfileStreamer implements AsyncConsumer<ResourceProfiles>, AutoCloseable {
  public static final Logger log = LoggerFactory.getLogger(PostgresProfileStreamer.class);
  private final ExecutorService queryQueue;
  private final PostgresProfileQueryHandler queryHandler;
  private boolean closed = false;

  public PostgresProfileStreamer(DataSource dataSource, long datasetId) throws SQLException {
    this.queryQueue = Executors.newSingleThreadExecutor();
    this.queryHandler = new PostgresProfileQueryHandler(dataSource, datasetId);
  }

  @Override
  public void accept(final ResourceProfiles resourceProfiles) {
    if (closed) throw new IllegalStateException("accept cannot be called on a closed PostgresProfileStreamer");
    queryQueue.submit(() -> {
      queryHandler.uploadResourceProfiles(resourceProfiles);
      log.info("%s : uploaded %d resource profiles".formatted(Duration.microseconds(System.nanoTime() / 1000), resourceProfiles.discreteProfiles().size() + resourceProfiles.realProfiles().size()));
    });
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    queryQueue.close();  // This waits for all submitted jobs to complete before returning
    try {
        queryHandler.close();
    } catch (SQLException e) {
        throw new DatabaseException("Error occurred while attempting to close PostgresProfileQueryHandler", e);
    }
  }

}
