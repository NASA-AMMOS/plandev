package gov.nasa.jpl.aerie.orchestration.simulation;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;

import javax.json.Json;
import javax.json.JsonReader;
import javax.json.stream.JsonGenerator;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphFlattener;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.simulationArgumentsP;


public class SimulationResultsWriter {
  private final static double SCHEMA_VERSION = 1;

  // Write JSONs with Pretty Printing
  private final static Map<String,String> config = Map.of(JsonGenerator.PRETTY_PRINTING, "");

  private final SimulationResults results;
  private final Plan plan;
  private final ResourceFileStreamer resourceFileStreamer;

  /**
   * Creates a SimulationResultsWriter that will write SimulationResults generated
   * using a StreamingResourceManager using the provided ResourceFileStreamer.
   * @param results The SimulationResults to be written
   * @param plan The Plan simulated
   * @param rfs The ResourceFileStreamer used during the simulation
   */
  public SimulationResultsWriter(SimulationResults results, Plan plan, ResourceFileStreamer rfs) {
    this.results = results;
    this.plan = plan;
    this.resourceFileStreamer = rfs;
  }

  /**
   * Create a SimulationResultsWriter that will write SimulationResults generated
   * using an InMemorySimulationResourceManager.
   * @param results The SimulationResults to be written
   * @param plan The plan simulated
   */
  public SimulationResultsWriter(SimulationResults results, Plan plan) {
    this(results, plan, null);
  }

  /**
   * Write the formatted SimulationResult JSON to System.out
   * @param canceledListener The CanceledListener used during simulation.
   *    Used to determine if the results represent a canceled simulation.
   */
  public void writeResults(CanceledListener canceledListener) {
    try (final var outWriter = new OutputStreamWriter(System.out)) {
      writeResults(canceledListener, outWriter);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Write the formatted SimulationResult JSON to the specified file.
   * @param canceledListener The CanceledListener used during simulation.
   *    Used to determine if the results represent a canceled simulation.
   * @param outputFilePath The file path to write results to.
   */
  public void writeResults(CanceledListener canceledListener, Path outputFilePath) {
    try (final var fileWriter = new FileWriter(outputFilePath.toFile())) {
      writeResults(canceledListener, fileWriter);
      System.out.println("Results written to "+outputFilePath);
    } catch (IOException e) {
      throw new RuntimeException("Unable to write to file: "+outputFilePath, e);
    }
  }

  public void writeResults(CanceledListener canceledListener, Writer outputWriter) {
    try (final var resultsJsonGenerator = Json.createGeneratorFactory(config).createGenerator(outputWriter)) {
      // Start the top-level object
      resultsJsonGenerator.writeStartObject();

      // Output the starting information, a set of top-level fields
      writeOpening(resultsJsonGenerator, canceledListener.get());

      // Write each of the main subsections

      resultsJsonGenerator.writeKey("simulationConfiguration");
      writeSimConfig(resultsJsonGenerator);

      resultsJsonGenerator.writeKey("profiles");
      writeProfiles(resultsJsonGenerator);

      resultsJsonGenerator.writeKey("spans");
      writeSpans(resultsJsonGenerator);

      resultsJsonGenerator.writeKey("topics");
      writeTopics(resultsJsonGenerator);

      resultsJsonGenerator.writeKey("events");
      writeEvents(resultsJsonGenerator);

      // End the top-level object
      resultsJsonGenerator.writeEnd();
    }
  }

  /** Write the top-level fields of the results JSON */
  private void writeOpening(JsonGenerator resultsGenerator, boolean canceled) {
    final var simEndTime = plan.simulationStartTimestamp.plusMicros(results.duration.in(Duration.MICROSECOND));

    resultsGenerator
        .write("version", SCHEMA_VERSION)
        .write("simulationStartTime", plan.simulationStartTimestamp.toString())
        .write("simulationEndTime", simEndTime.toString())
        .write("canceled", canceled);
  }

  /** Write the simulation configuration section of the results */
  private void writeSimConfig(JsonGenerator resultsGenerator) {
    resultsGenerator.writeStartObject()
        .write("startTime", plan.simulationStartTimestamp.toString())
        .write("endTime", plan.simulationEndTimestamp.toString())
        .write("arguments", simulationArgumentsP.unparse(plan.simulationConfiguration()))
        .writeEnd();
  }

  /**
   * Write the profiles section of the results.
   * Will get profile segments from resourceFileStreamer if it's non-null, or from results' maps otherwise.
   */
  private void writeProfiles(JsonGenerator resultsGenerator) {
    resultsGenerator.writeStartObject();

    // Each real profile is an object in the array realProfiles
    resultsGenerator.writeStartArray("realProfiles");
    for (var e : results.realProfiles.entrySet()) {
      writeProfile(resultsGenerator, e.getKey(), e.getValue(), realDynamicsP);
    }
    resultsGenerator.writeEnd();

    // Each discrete profile is an object in the array discreteProfiles
    resultsGenerator.writeStartArray("discreteProfiles");
    for (var e : results.discreteProfiles.entrySet()) {
      writeProfile(resultsGenerator, e.getKey(), e.getValue(), serializedValueP);
    }
    resultsGenerator.writeEnd();

    resultsGenerator.writeEnd(); // end of profiles object
  }

  /** Write a single resource profile object */
  private <D> void writeProfile(
      JsonGenerator resultsGenerator,
      String profileName,
      ResourceProfile<D> profile,
      JsonParser<D> dynamicsParser
  ) {
    resultsGenerator.writeStartObject()
                    .write("name", profileName)
                    .write("schema", valueSchemaP.unparse(profile.schema()))
                    .writeStartArray("segments");

    if (resourceFileStreamer != null) {
      // We expect RFS made a temp file where each line is a profile segment
      var resourceTempFile = Path.of(resourceFileStreamer.getFileName(profileName));
      try (final var stream = Files.lines(resourceTempFile)) {
        stream.forEach(s -> {
          if (!s.isBlank()) {
            // s is a JSON object for a single segment, write it as a value to the results generator
            // Sadly, this requires reading the object into a JsonValue, just to write it back out (!)
            try (final JsonReader jr = Json.createReader(new StringReader(s))) {
              resultsGenerator.write(jr.readValue());
            }
          }
        });
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
      resourceTempFile.toFile().delete();
    } else {
      for (var s : profile.segments()) {
        resultsGenerator.writeStartObject()
                        .write("extent", s.extent().toString())
                        .write("dynamics", dynamicsParser.unparse(s.dynamics()))
                        .writeEnd();
      }
    }

    resultsGenerator.writeEnd().writeEnd();
  }

  /** Write the spans section of the results file, containing all activity spans */
  private void writeSpans(JsonGenerator resultsGenerator) {
    resultsGenerator.writeStartObject();

    // Each simulated activity is an object in the array simulatedActivities
    resultsGenerator.writeStartArray("simulatedActivities");
    for (var e : results.simulatedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      resultsGenerator.writeStartObject();

      final var startOffset = Duration.of(plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())), Duration.MICROSECOND).toString();
      final var endTime = act.start().plus(act.duration().in(Duration.MICROSECOND), ChronoUnit.MICROS).toString();

      resultsGenerator.write("id", id.id());

      resultsGenerator.writeKey("directiveId");
      act.directiveId().ifPresentOrElse(
          did -> resultsGenerator.write(did.id()),
          resultsGenerator::writeNull);

      resultsGenerator.writeKey("parentId");
      if (act.parentId() != null) {
        resultsGenerator.write(act.parentId().id());
      } else {
        resultsGenerator.writeNull();
      }

      resultsGenerator.writeStartArray("childIds");
      for (var ci : act.childIds()) resultsGenerator.write(ci.id());
      resultsGenerator.writeEnd();

      resultsGenerator
          .write("type", act.type())
          .write("startOffset", startOffset)
          .write("duration", act.duration().toString())
          .write("attributes", serializedValueP.unparse(act.computedAttributes()))
          .write("arguments", activityArgumentsP.unparse(act.arguments()))
          .write("startTime", act.start().toString())
          .write("endTime", endTime);

      resultsGenerator.writeEnd();
    }
    resultsGenerator.writeEnd();

    // Each unfinished activity is an object in the array unfinishedActivities
    resultsGenerator.writeStartArray("unfinishedActivities");
    for (var e : results.unfinishedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      resultsGenerator.writeStartObject();

      final var startOffset = Duration.of(plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())), Duration.MICROSECOND).toString();

      resultsGenerator.write("id", id.id());

      resultsGenerator.writeKey("directiveId");
      act.directiveId().ifPresentOrElse(
          did -> resultsGenerator.write(did.id()),
          resultsGenerator::writeNull);

      resultsGenerator.writeKey("parentId");
      if (act.parentId() != null) {
        resultsGenerator.write(act.parentId().id());
      } else {
        resultsGenerator.writeNull();
      }

      resultsGenerator.writeStartArray("childIds");
      for (var ci : act.childIds()) resultsGenerator.write(ci.id());
      resultsGenerator.writeEnd();

      resultsGenerator
          .write("type", act.type())
          .write("startOffset", startOffset)
          .write("arguments", activityArgumentsP.unparse(act.arguments()))
          .write("startTime", act.start().toString());

      resultsGenerator.writeEnd();
    }
    resultsGenerator.writeEnd();

    resultsGenerator.writeEnd(); // end of spans object
  }

  /** Write the topics section of the results */
  private void writeTopics(JsonGenerator resultsGenerator) {
    resultsGenerator.writeStartObject();
    for (var t : results.topics) {
      resultsGenerator.writeStartObject(t.getMiddle())
          .write("schema", valueSchemaP.unparse(t.getRight()))
          .writeEnd();
    }
    resultsGenerator.writeEnd(); // end of topics object
  }

  /** Write the events section of the results */
  private void writeEvents(JsonGenerator resultsGenerator) {
    resultsGenerator.writeStartArray();

    for (var e : results.events.entrySet()) {
      var realTime = e.getKey();
      var transactions = e.getValue();

      int transactionIndex = 0;
      for (var eventGraph : transactions) {
        var flattenedEventGraph = EventGraphFlattener.flatten(eventGraph);

        for (var entry : flattenedEventGraph) {
          var event = entry.getRight();

          resultsGenerator.writeStartObject()
              .write("causalTime", entry.getLeft())
              .write("realTime", realTime.toString())
              .write("transactionIndex", transactionIndex)
              .write("value", serializedValueP.unparse(event.value()));

          //grab the topic from the event's topic id
          results.topics
              .stream()
              .filter(topic -> topic.getLeft() == event.topicId())
              .findFirst()
              .ifPresent(topic -> resultsGenerator.write("topic", topic.getMiddle()));

          // optional span id
          resultsGenerator.writeKey("spanId");
          event.spanId().ifPresentOrElse(resultsGenerator::write, resultsGenerator::writeNull);

          resultsGenerator.writeEnd(); // end of event object
        }

        ++transactionIndex;
      }
    }

    resultsGenerator.writeEnd(); // end of events array
  }
}

/*
Json Schema for Sim results:

{
version: 1.0
simulationStartTime: Timestamp (2024-07-01T00:00:00Z)
simulationEndTime: Timestamp // When the simulation stopped
canceled: boolean

simulationConfiguration: {
   startTime: Timestamp
   endTime: Timestamp
   arguments: {}
}

profiles: {
  realProfiles: [
   {
     name: string
     schema: ValueSchema
     segments: [
       extent: Duration
       dynamics: {}//arbitrary value based on schema
     ]
   }
  ],
  discreteProfiles: [
    {
     name: string
     schema: ValueSchema
     segments: [
       extent: Duration
       dynamics: {}//arbitrary value based on schema
     ]
   }
  ]
}

spans: {
  simulatedActivities: [
    {
      id: int
      directiveId: int | null
      parentId: int | null
      childIds: [int]
      type: String
      startOffset: Duration
      duration: Duration
      attributes: {}
      arguments: {}
      startTime: Timestamp
      endTime: Timestamp
    }
  ],
  unfinishedActivities: [
    {
      id: int
      directiveId: int | null
      parentId: int | null
      childIds: [int]
      type: string
      startOffset: Duration
      arguments: {}
      startTime: Timestamp
    }
  ]
}

events: {
  causalTime : string,
  realTime : Timestamp,
  transactionIndex : int,
  value : {},
  topic: {
    name : string
    valueSchema : {}
  }
  spanId: int,
}
}
 */
