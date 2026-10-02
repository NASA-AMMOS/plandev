package gov.nasa.ammos.plandev.merlin.server.remotes.postgres;

import gov.nasa.ammos.plandev.merlin.server.exceptions.NoSuchPlanImportRequest;
import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

final class SetPlanImportRequestStatusAction implements AutoCloseable {
  private enum PlanImportRequestStatus {
    FAILURE ("failed"),
    SUCCESS ("complete"),
    EXTRACTING_MODEL ("extracting_model"),
    IMPORTING_PLAN ("importing_plan"),
    IMPORTING_DATASET ("importing_dataset");

    final private String dbName;
    PlanImportRequestStatus(String dbName) {
      this.dbName = dbName;
    }

    public String getDbName() {
      return dbName;
    }
  }

  private final @Language("SQL") String sql = """
      update merlin.plan_import_request
      set
          status = ?::merlin.plan_import_request_status,
          reason = ?::jsonb
      where id = ?;
      """;

  private final PreparedStatement statement;

  public SetPlanImportRequestStatusAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void succeed(int requestId) throws SQLException {
    statement.setString(1, PlanImportRequestStatus.SUCCESS.getDbName());
    statement.setNull(2, Types.VARCHAR);
    statement.setInt(3, requestId);

    final var count = this.statement.executeUpdate();
    if (count < 1) throw new NoSuchPlanImportRequest(requestId);
    if (count > 1) throw new Error("More than one row affected by dataset update by primary key. Is the database corrupted?");
  }

  public void fail(int requestId, PlanImportFailure failureReason) throws SQLException {
    statement.setString(1, PlanImportRequestStatus.FAILURE.getDbName());
    statement.setString(2, failureReason.toJsonString());
    statement.setInt(3, requestId);

    final var count = this.statement.executeUpdate();
    if (count < 1) throw new NoSuchPlanImportRequest(requestId);
    if (count > 1) throw new Error("More than one row affected by dataset update by primary key. Is the database corrupted?");
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
