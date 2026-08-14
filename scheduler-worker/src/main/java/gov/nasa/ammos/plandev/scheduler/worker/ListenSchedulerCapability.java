package gov.nasa.jpl.aerie.scheduler.worker;

import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.DatabaseException;
import gov.nasa.jpl.aerie.scheduler.worker.postgres.ListenSchedulingRequestStatusAction;
import gov.nasa.jpl.aerie.scheduler.worker.postgres.PostgresSchedulingRequestNotificationPayload;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.sql.DataSource;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;

import static gov.nasa.jpl.aerie.scheduler.worker.postgres.PostgresNotificationJsonParsers.postgresSchedulingRequestNotificationP;

public class ListenSchedulerCapability {
  private static final Logger logger = LoggerFactory.getLogger(ListenSchedulerCapability.class);

  private final DataSource dataSource;
  private final BlockingQueue<PostgresSchedulingRequestNotificationPayload> notificationQueue;

  public ListenSchedulerCapability(
      final DataSource dataSource,
      final BlockingQueue<PostgresSchedulingRequestNotificationPayload> notificationQueue) {
    this.dataSource = dataSource;
    this.notificationQueue = notificationQueue;
  }

  public Thread registerListener(SchedulingCanceledListener canceledListener) {
    final var listenerThread = new Thread(() -> {
      try (final var connection = this.dataSource.getConnection()) {
        try (final var listenSimulationStatusAction = new ListenSchedulingRequestStatusAction(connection)) {
          listenSimulationStatusAction.apply();
        } catch (final SQLException ex) {
          throw new DatabaseException("Failed to register as LISTEN to postgres database.", ex);
        }

        while (!Thread.currentThread().isInterrupted()) {
          final var pgConnection = connection.unwrap(PGConnection.class);
          final var notifications = pgConnection.getNotifications(10000);
          if (notifications != null) {
            for (final var notification : notifications) {
              final var processId = notification.getPID();
              final var channelName = notification.getName();
              final var payload = notification.getParameter();

              logger.info("Received PSQL Notification: {}, {}, {}", processId, channelName, payload);

              if (channelName.equals("scheduling_cancel")) {
                  canceledListener.receiveSignal(new SpecificationId(Long.parseLong(payload)));
              } else {
                try (final var reader = Json.createReader(new StringReader(payload))) {
                  final var jsonValue = reader.readValue();
                  final var notificationPayload = postgresSchedulingRequestNotificationP
                      .parse(jsonValue)
                      .getSuccessOrThrow();
                  try {
                    this.notificationQueue.put(notificationPayload);
                  } catch (InterruptedException e) {
                    // This thread will be interrupted when the worker's main loop exits, so it should exit gracefully:
                    logger.info("Listener has been interrupted");
                    return;
                  }
                }
              }
            }
          }
        }
        logger.info("Listener has received interrupted signal");
      } catch (SQLException e) {
        throw new DatabaseException("Listener encountered exception", e);
      }
    });
    listenerThread.start();
    return listenerThread;
  }
}
