package gov.nasa.jpl.aerie.orchestration;

import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.jetbrains.annotations.NotNull;

import javax.json.Json;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;

public class GoalSpecificationParser {
  public record GoalRecord(
      int order,
      Path jarPath,
      Map<String, SerializedValue> args,
      boolean simulateAfter
  ) implements Comparable<GoalRecord> {
    @Override
    public int compareTo(@NotNull final GoalRecord o) {
      return Integer.compare(this.order, o.order);
    }
  }

  /**
   * Parse a Goal Specification into an ordered list of GoalRecords.
   *
   * Goal Specification Schema:
   * {
   *   "version": 1.0
   *   "goals": [
   *    {
   *      "order": int
   *      "jarPath": string
   *      "arguments": {}
   *      "simulateAfter": boolean
   *    }, ...
   *  ]
   * }
   *
   * @param filePath Path to Goal Specification JSON
   * @return A List of GoalRecords, sorted by the "order" fields provided in the JSON
   * TODO: Consider implications of same order twice
   */
  public static List<GoalRecord> parseGoalSpecification(final Path filePath) {
    try (final var fileReader = new FileReader(filePath.toString())) {
      final var parser = Json.createParser(fileReader);
      parser.next();

      final var goalSpecArray = parser.getObject().getJsonArray("goals");


      final var goalSpec = new ArrayList<GoalRecord>();
      for(final var specValue: goalSpecArray) {
        final var spec = specValue.asJsonObject();
        final var args = new HashMap<String, SerializedValue>();
        spec.getJsonObject("arguments").forEach((key, value) -> args.put(key, serializedValueP.parse(value)
                                                                                              .getSuccessOrThrow()));

        goalSpec.add(new GoalRecord(
            spec.getInt("order"),
            Path.of(spec.getString("jarPath")),
            args,
            spec.getBoolean("simulateAfter")));
      }
      goalSpec.sort(GoalRecord::compareTo);
      return goalSpec;
    } catch (final FileNotFoundException e) {
      throw new RuntimeException("Specified goal specification JSON file does not exist: " + filePath);
    } catch (final Exception e) {
      throw new RuntimeException("Error while reading goal specification JSON file: " + filePath, e);
    }
  }
}
