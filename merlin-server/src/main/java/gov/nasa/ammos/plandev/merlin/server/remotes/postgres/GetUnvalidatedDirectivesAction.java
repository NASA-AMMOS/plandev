package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.MissionModelId;
import gov.nasa.jpl.aerie.types.SerializedActivity;
import gov.nasa.jpl.aerie.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.jpl.aerie.merlin.server.models.PlanId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.getJsonColumn;

public class GetUnvalidatedDirectivesAction implements AutoCloseable {
  private static final String sql = """
      select ad.id, ad.plan_id, ad.type, ad.arguments, p.model_id, adv.last_modified_arguments_at
        from merlin.activity_directive ad
        join merlin.activity_directive_validations adv
          on ad.id = adv.directive_id and ad.plan_id = adv.plan_id
        join merlin.plan p
          on ad.plan_id = p.id
        where adv.status = 'pending';
      """;

  private final PreparedStatement statement;

  public GetUnvalidatedDirectivesAction(Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public Map<MissionModelId, List<ActivityDirectiveForValidation>> get() throws SQLException {
    final var results = this.statement.executeQuery();
    final var map = new HashMap<MissionModelId, List<ActivityDirectiveForValidation>>();

    while (results.next()) {
      final var modelId = new MissionModelId(results.getInt("model_id"));
      final var directiveId = results.getInt("id");
      final var planId = results.getInt("plan_id");
      final var type = results.getString("type");
      final var arguments = getJsonColumn(results, "arguments", activityArgumentsP)
          .getSuccessOrThrow($ -> new Error("Corrupt activity arguments cannot be parsed: " + $.reason()));
      final var argumentTimestamp = results.getTimestamp("last_modified_arguments_at");

      map.computeIfAbsent(modelId, $ -> new ArrayList<>())
         .add(new ActivityDirectiveForValidation(
             new ActivityDirectiveId(directiveId),
             new PlanId(planId),
             argumentTimestamp,
             new SerializedActivity(type, arguments)
         ));
    }

    return map;
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
