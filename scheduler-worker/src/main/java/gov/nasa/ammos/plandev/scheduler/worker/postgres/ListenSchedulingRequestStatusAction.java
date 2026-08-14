package gov.nasa.jpl.aerie.scheduler.worker.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.intellij.lang.annotations.Language;

/*package local*/ public class ListenSchedulingRequestStatusAction implements AutoCloseable{
  private static final @Language("SQL") String sql = """
    LISTEN "scheduling_request_notification";
    LISTEN "scheduling_cancel";
  """;

  private final PreparedStatement statement;

  public ListenSchedulingRequestStatusAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply() throws SQLException {
    this.statement.executeUpdate();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
