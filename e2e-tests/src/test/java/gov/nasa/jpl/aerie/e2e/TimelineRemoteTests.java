package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.timeline.Duration;
import gov.nasa.jpl.aerie.timeline.Interval;
import gov.nasa.jpl.aerie.timeline.collections.profiles.Real;
import gov.nasa.jpl.aerie.timeline.payloads.LinearEquation;
import gov.nasa.jpl.aerie.timeline.payloads.Segment;
import gov.nasa.jpl.aerie.timeline.plan.AeriePostgresPlan;
import gov.nasa.jpl.aerie.timeline.plan.AeriePostgresSimulationResults;
import gov.nasa.jpl.aerie.timeline.plan.Plan;
import gov.nasa.jpl.aerie.timeline.plan.SimulatedPlan;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.json.Json;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TimelineRemoteTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  // Per-Test Data
  private int modelId;
  private int planId;
  private int activityId;

  private SimulatedPlan simulatedPlan;
  private Connection connection;
  private HikariDataSource dataSource;
  @BeforeAll
  void beforeAll() throws SQLException {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);

    // Connect to the database
    final var hikariConfig = new HikariConfig();

    hikariConfig.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");

    hikariConfig.addDataSourceProperty("serverName", "localhost");
    hikariConfig.addDataSourceProperty("portNumber", 5432);
    hikariConfig.addDataSourceProperty("databaseName", "aerie");
    hikariConfig.addDataSourceProperty("applicationName", "Merlin Server");
    hikariConfig.setUsername(System.getenv("MERLIN_USERNAME"));
    hikariConfig.setPassword(System.getenv("MERLIN_PASSWORD"));

    hikariConfig.setConnectionInitSql("set time zone 'UTC'");
    dataSource = new HikariDataSource(hikariConfig);
    connection = dataSource.getConnection();
  }

  @AfterAll
  void afterAll() throws SQLException {
    // Cleanup Requests
    hasura.close();
    playwright.close();
    connection.close();
    dataSource.close();
  }

  @BeforeEach
  void beforeEach() throws IOException, InterruptedException {
    // Insert the Mission Model
    try (final var gateway = new GatewayRequests(playwright)) {
      modelId = hasura.createMissionModel(
          gateway.uploadJarFile(),
          "Banananation (e2e tests)",
          "aerie_e2e_tests",
          "Timeline Remote Tests");
    }
    // Insert the Plan
    planId = hasura.createPlan(
        modelId,
        "Test Plan - Timeline Remote Tests",
        "1212h",
        "2021-01-01T00:00:00Z");
    //Insert the Activity
    activityId = hasura.insertActivity(
        planId,
        "BiteBanana",
        "1h",
        Json.createObjectBuilder().add("biteSize", 1).build());
    int simDatasetId = hasura.awaitSimulation(planId).simDatasetId();

    plan = new AeriePostgresPlan(connection, simDatasetId);
    final var simResults = new AeriePostgresSimulationResults(connection, simDatasetId, plan);
    simulatedPlan = new SimulatedPlan(plan, simResults);
  }

  @AfterEach
  void afterEach() throws IOException {
    hasura.deletePlan(planId);
    hasura.deleteMissionModel(modelId);
  }

  @Test
  void queryActivityInstances() {
    final var instances = simulatedPlan.instances().collect();
    assertEquals(1, instances.size());
    final var instance = instances.get(0);
    assertEquals("BiteBanana", instance.getType());
    assertEquals(activityId, instance.directiveId);
    assertEquals(1, instance.inner.arguments.get("biteSize").asInt().get());
    assertEquals(Duration.ZERO, instance.getInterval().duration());
  }

  @Test
  void queryActivityDirectives() {
    final var directives = simulatedPlan.directives().collect();
    assertEquals(1, directives.size());
    final var directive = directives.get(0);
    assertEquals("BiteBanana", directive.getType());
    assertEquals(activityId, directive.id);
    assertEquals(1, directive.inner.arguments.get("biteSize").asInt().get());
  }

  @Test
  void queryResources() {
    final var fruit = simulatedPlan.resource("/fruit", Real::deserialize).collect();
    assertIterableEquals(
        List.of(
            Segment.of(Interval.betweenClosedOpen(Duration.ZERO, Duration.HOUR), new LinearEquation(4.0)),
            Segment.of(Interval.betweenClosedOpen(Duration.HOUR, Duration.HOUR.times(1212)), new LinearEquation(3.0))
        ),
        fruit
    );
  }
}
