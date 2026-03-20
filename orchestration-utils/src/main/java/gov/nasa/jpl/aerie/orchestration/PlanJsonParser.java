package gov.nasa.jpl.aerie.orchestration;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.pgTimestampP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.simulationArgumentsP;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Class to parse a plan.json file.
 */
public class PlanJsonParser {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private PlanJsonParser() {}

  /**
   * Parses a plan.json file that has been exported by the Aerie Gateway.
   */
  public static Plan parsePlan(final Path filePath) {
    try (final var fileReader = new FileReader(filePath.toString())) {
      final var planObject = (ObjectNode) objectMapper.readTree(fileReader);

      final var name = planObject.get("name").textValue();
      final var duration = Duration.fromString(planObject.get("duration").textValue());
      final Timestamp startTime = pgTimestampP.parse(planObject.get("start_time")).getSuccessOrThrow();
      final Timestamp endTime = startTime.plusMicros(duration.in(Duration.MICROSECOND));

      final var activityDirectives = parseActivities(planObject.get("activities"));
      final var simulationConfig = parseSimulationConfiguration((ObjectNode) planObject.get("simulation_arguments"));

      return new Plan(name, startTime, endTime, activityDirectives, simulationConfig);
    } catch (final FileNotFoundException e) {
      throw new RuntimeException("Specified plan JSON file does not exist: " + filePath);
    } catch (final Exception e) {
      throw new RuntimeException("Error while reading plan JSON file: " + filePath, e);
    }
  }

  /**
   * Parse an array of JSON activities into a map of ActivityDirectives
   *
   * @param activities the json array directives to be parsed
   */
  private static Map<ActivityDirectiveId, ActivityDirective> parseActivities(final JsonNode activities) {
    final var activitiesMap = new HashMap<ActivityDirectiveId, ActivityDirective>(activities.size());

    for (final var a : activities) {
      final var id = new ActivityDirectiveId(a.get("id").intValue());
      final var startOffset = Duration.fromString(a.get("start_offset").textValue());
      final var type = a.get("type").textValue();
      final var anchoredToStart = a.get("anchored_to_start").booleanValue();
      final var anchorId = a.get("anchor_id").isNull() ? null : new ActivityDirectiveId(a.get("anchor_id").intValue());
      final var arguments = activityArgumentsP.parse(a.get("arguments")).getSuccessOrThrow();

      activitiesMap.put(
          id,
          new ActivityDirective(
              startOffset,
              type,
              arguments,
              anchorId,
              anchoredToStart
          ));
    }

    return activitiesMap;
  }

  /**
   * Parses the simulation configuration from a jsonObject into a Map
   *
   * @param simConfig the ObjectNode containing the simulation configuration
   * @return A map containing the parsed simulation configuration
   **/
  private static Map<String, SerializedValue> parseSimulationConfiguration(final ObjectNode simConfig) {
    // Return if we don't have any simConfigs
    if (simConfig.isEmpty())
      return Map.of();
    return simulationArgumentsP.parse(simConfig).getSuccessOrThrow();
  }


  /**
   * Parses a Simulation Configuration JSON file and updates the given plan accordingly.
   *
   * Schema for a Simulation Configuration:
   * {
   *   version: "2"
   *   simulation_start_time: string (PG Timestamp)
   *   simulation_end_time: string (PG Timestamp)
   *   arguments: json object
   * }
   *
   * @param filePath path to the config file
   * @param plan plan object to be updated
   */
  public static void parseSimulationConfiguration(final Path filePath, final Plan plan) {
    try (final var fileReader = new FileReader(filePath.toString())) {
      final var configObject = (ObjectNode) objectMapper.readTree(fileReader);

      final var simStartTime = pgTimestampP.parse(configObject.get("simulation_start_time")).getSuccessOrThrow();
      final var simEndTime = pgTimestampP.parse(configObject.get("simulation_end_time")).getSuccessOrThrow();
      final var config = PlanJsonParser.parseSimulationConfiguration((ObjectNode) configObject.get("arguments"));

      plan.simulationConfiguration().putAll(config);
      plan.simulationStartTimestamp = simStartTime;
      plan.simulationEndTimestamp = simEndTime;

    } catch (final FileNotFoundException e) {
      throw new RuntimeException("Specified simulation configuration JSON file does not exist: " + filePath);
    } catch (final Exception e) {
      throw new RuntimeException("Error while reading simulation configuration JSON file: " + filePath, e);
    }
  }
}
