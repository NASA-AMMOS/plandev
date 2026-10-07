package gov.nasa.ammos.plandev.workspace.server.postgres;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceRevisionStore;
import org.intellij.lang.annotations.Language;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PostgresRevisionStore implements WorkspaceRevisionStore {
  private static final @Language("SQL") String insertSql = """
    insert into sequencing.workspace_file_revision
      (id, workspace_id, file_id, ordinal, display_name, path_at_revision, git_commit_sha, created_by, created_at)
    values (?, ?, ?, ?, ?, ?, ?, ?, ?);
    """;
  private static final @Language("SQL") String deleteWorkspaceSql = """
    delete from sequencing.workspace_file_revision where workspace_id = ?;
    """;
  private static final @Language("SQL") String listSql = """
    select * from sequencing.workspace_file_revision
    where workspace_id = ? and file_id = ?
    order by ordinal;
    """;
  private static final @Language("SQL") String getSql = """
    select * from sequencing.workspace_file_revision
    where workspace_id = ? and id = ?;
    """;

  private final DataSource dataSource;

  public PostgresRevisionStore(final DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void insert(final Revision revision) throws SQLException {
    try (final var connection = dataSource.getConnection();
         final var statement = connection.prepareStatement(insertSql)) {
      bind(statement, revision);
      statement.executeUpdate();
    }
  }

  @Override
  public void replaceWorkspaceRevisions(final int workspaceId, final List<Revision> revisions) throws SQLException {
    try (final var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (final var statement = connection.prepareStatement(deleteWorkspaceSql)) {
          statement.setInt(1, workspaceId);
          statement.executeUpdate();
        }
        try (final var statement = connection.prepareStatement(insertSql)) {
          for (final var revision : revisions) {
            bind(statement, revision);
            statement.addBatch();
          }
          statement.executeBatch();
        }
        connection.commit();
      } catch (SQLException e) {
        try {
          connection.rollback();
        } catch (SQLException rollback) {
          e.addSuppressed(rollback);
        }
        throw e;
      }
    }
  }

  @Override
  public List<Revision> list(final int workspaceId, final UUID fileId) throws SQLException {
    try (final var connection = dataSource.getConnection();
         final var statement = connection.prepareStatement(listSql)) {
      statement.setInt(1, workspaceId);
      statement.setObject(2, fileId);
      try (final var res = statement.executeQuery()) {
        final var out = new ArrayList<Revision>();
        while (res.next()) out.add(revision(res));
        return out;
      }
    }
  }

  @Override
  public Optional<Revision> get(final int workspaceId, final UUID revisionId) throws SQLException {
    try (final var connection = dataSource.getConnection();
         final var statement = connection.prepareStatement(getSql)) {
      statement.setInt(1, workspaceId);
      statement.setObject(2, revisionId);
      try (final var res = statement.executeQuery()) {
        return res.next() ? Optional.of(revision(res)) : Optional.empty();
      }
    }
  }

  private static void bind(final PreparedStatement statement, final Revision revision) throws SQLException {
    statement.setObject(1, revision.id());
    statement.setInt(2, revision.workspaceId());
    statement.setObject(3, revision.fileId());
    statement.setLong(4, revision.ordinal());
    statement.setString(5, revision.name());
    statement.setString(6, revision.pathAtRevision());
    statement.setString(7, revision.commitSha());
    statement.setString(8, revision.createdBy());
    statement.setObject(9, OffsetDateTime.ofInstant(revision.createdAt(), ZoneOffset.UTC));
  }

  private static Revision revision(final ResultSet res) throws SQLException {
    return new Revision(
        res.getObject("id", UUID.class),
        res.getInt("workspace_id"),
        res.getObject("file_id", UUID.class),
        res.getLong("ordinal"),
        res.getString("display_name"),
        res.getString("path_at_revision"),
        res.getString("git_commit_sha"),
        res.getString("created_by"),
        res.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
