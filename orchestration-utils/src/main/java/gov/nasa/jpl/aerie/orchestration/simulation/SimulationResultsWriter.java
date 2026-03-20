package gov.nasa.jpl.aerie.orchestration.simulation;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphFlattener;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;

import gov.nasa.jpl.aerie.merlin.driver.json.JsonEncoding;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.simulationArgumentsP;


public class SimulationResultsWriter {
  private final static double SCHEMA_VERSION = 1;

  private static final ObjectMapper objectMapper = new ObjectMapper();

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
    try (final var resultsJsonGenerator = objectMapper.createGenerator(outputWriter)) {
      resultsJsonGenerator.useDefaultPrettyPrinter();
      // Start the top-level object
      resultsJsonGenerator.writeStartObject();

      // Output the starting information, a set of top-level fields
      writeOpening(resultsJsonGenerator, canceledListener.get());

      // Write each of the main subsections

      resultsJsonGenerator.writeFieldName("simulationConfiguration");
      writeSimConfig(resultsJsonGenerator);

      resultsJsonGenerator.writeFieldName("profiles");
      writeProfiles(resultsJsonGenerator);

      resultsJsonGenerator.writeFieldName("spans");
      writeSpans(resultsJsonGenerator);

      resultsJsonGenerator.writeFieldName("topics");
      writeTopics(resultsJsonGenerator);

      resultsJsonGenerator.writeFieldName("events");
      writeEvents(resultsJsonGenerator);

      // End the top-level object
      resultsJsonGenerator.writeEndObject();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Write the top-level fields of the results JSON */
  private void writeOpening(JsonGenerator resultsGenerator, boolean canceled) throws IOException {
    final var simEndTime = plan.simulationStartTimestamp.plusMicros(results.duration.in(Duration.MICROSECOND));

    resultsGenerator.writeNumberField("version", SCHEMA_VERSION);
    resultsGenerator.writeStringField("simulationStartTime", plan.simulationStartTimestamp.toString());
    resultsGenerator.writeStringField("simulationEndTime", simEndTime.toString());
    resultsGenerator.writeBooleanField("canceled", canceled);
  }

  /** Write the simulation configuration section of the results */
  private void writeSimConfig(JsonGenerator resultsGenerator) throws IOException {
    resultsGenerator.writeStartObject();
    resultsGenerator.writeStringField("startTime", plan.simulationStartTimestamp.toString());
    resultsGenerator.writeStringField("endTime", plan.simulationEndTimestamp.toString());
    resultsGenerator.writeFieldName("arguments");
    resultsGenerator.writeTree(simulationArgumentsP.unparse(plan.simulationConfiguration()));
    resultsGenerator.writeEndObject();
  }

  /**
   * Write the profiles section of the results.
   * Will get profile segments from resourceFileStreamer if it's non-null, or from results' maps otherwise.
   */
  private void writeProfiles(JsonGenerator resultsGenerator) throws IOException {
    resultsGenerator.writeStartObject();

    // Each real profile is an object in the array realProfiles
    resultsGenerator.writeArrayFieldStart("realProfiles");
    for (var e : results.realProfiles.entrySet()) {
      writeProfile(resultsGenerator, e.getKey(), e.getValue(), realDynamicsP);
    }
    resultsGenerator.writeEndArray();

    // Each discrete profile is an object in the array discreteProfiles
    resultsGenerator.writeArrayFieldStart("discreteProfiles");
    for (var e : results.discreteProfiles.entrySet()) {
      writeProfile(resultsGenerator, e.getKey(), e.getValue(), serializedValueP);
    }
    resultsGenerator.writeEndArray();

    resultsGenerator.writeEndObject(); // end of profiles object
  }

  /** Write a single resource profile object */
  private <D> void writeProfile(
      JsonGenerator resultsGenerator,
      String profileName,
      ResourceProfile<D> profile,
      JsonParser<D> dynamicsParser
  ) throws IOException {
    resultsGenerator.writeStartObject();
    resultsGenerator.writeStringField("name", profileName);
    resultsGenerator.writeFieldName("schema");
    resultsGenerator.writeTree(valueSchemaP.unparse(profile.schema()));
    resultsGenerator.writeArrayFieldStart("segments");

    if (resourceFileStreamer != null) {
      // We expect RFS made a temp file where each line is a profile segment
      var resourceTempFile = Path.of(resourceFileStreamer.getFileName(profileName));
      try (final var stream = Files.lines(resourceTempFile)) {
        stream.forEach(s -> {
          if (!s.isBlank()) {
            // s is a JSON object for a single segment, write it as a value to the results generator
            // Sadly, this requires reading the object into a JsonNode, just to write it back out (!)
            try {
              final JsonNode node = objectMapper.readTree(s);
              resultsGenerator.writeTree(node);
            } catch (IOException ex) {
              throw new RuntimeException(ex);
            }
          }
        });
      }
      resourceTempFile.toFile().delete();
    } else {
      for (var s : profile.segments()) {
        resultsGenerator.writeStartObject();
        resultsGenerator.writeStringField("extent", s.extent().toString());
        resultsGenerator.writeFieldName("dynamics");
        resultsGenerator.writeTree(dynamicsParser.unparse(s.dynamics()));
        resultsGenerator.writeEndObject();
      }
    }

    resultsGenerator.writeEndArray();
    resultsGenerator.writeEndObject();
  }

  /** Write the spans section of the results file, containing all activity spans */
  private void writeSpans(JsonGenerator resultsGenerator) throws IOException {
    resultsGenerator.writeStartObject();

    // Each simulated activity is an object in the array simulatedActivities
    resultsGenerator.writeArrayFieldStart("simulatedActivities");
    for (var e : results.simulatedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      resultsGenerator.writeStartObject();

      final var startOffset = Duration.of(plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())), Duration.MICROSECOND).toString();
      final var endTime = act.start().plus(act.duration().in(Duration.MICROSECOND), ChronoUnit.MICROS).toString();

      resultsGenerator.writeNumberField("id", id.id());

      resultsGenerator.writeFieldName("directiveId");
      act.directiveId().ifPresentOrElse(
          did -> {
            try { resultsGenerator.writeNumber(did.id()); } catch (IOException ex) { throw new RuntimeException(ex); }
          },
          () -> {
            try { resultsGenerator.writeNull(); } catch (IOException ex) { throw new RuntimeException(ex); }
          });

      resultsGenerator.writeFieldName("parentId");
      if (act.parentId() != null) {
        resultsGenerator.writeNumber(act.parentId().id());
      } else {
        resultsGenerator.writeNull();
      }

      resultsGenerator.writeArrayFieldStart("childIds");
      for (var ci : act.childIds()) resultsGenerator.writeNumber(ci.id());
      resultsGenerator.writeEndArray();

      resultsGenerator.writeStringField("type", act.type());
      resultsGenerator.writeStringField("startOffset", startOffset);
      resultsGenerator.writeStringField("duration", act.duration().toString());
      resultsGenerator.writeFieldName("attributes");
      resultsGenerator.writeTree(JsonEncoding.encode(act.computedAttributes()));
      resultsGenerator.writeFieldName("arguments");
      resultsGenerator.writeTree(activityArgumentsP.unparse(act.arguments()));
      resultsGenerator.writeStringField("startTime", act.start().toString());
      resultsGenerator.writeStringField("endTime", endTime);

      resultsGenerator.writeEndObject();
    }
    resultsGenerator.writeEndArray();

    // Each unfinished activity is an object in the array unfinishedActivities
    resultsGenerator.writeArrayFieldStart("unfinishedActivities");
    for (var e : results.unfinishedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      resultsGenerator.writeStartObject();

      final var startOffset = Duration.of(plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())), Duration.MICROSECOND).toString();

      resultsGenerator.writeNumberField("id", id.id());

      resultsGenerator.writeFieldName("directiveId");
      act.directiveId().ifPresentOrElse(
          did -> {
            try { resultsGenerator.writeNumber(did.id()); } catch (IOException ex) { throw new RuntimeException(ex); }
          },
          () -> {
            try { resultsGenerator.writeNull(); } catch (IOException ex) { throw new RuntimeException(ex); }
          });

      resultsGenerator.writeFieldName("parentId");
      if (act.parentId() != null) {
        resultsGenerator.writeNumber(act.parentId().id());
      } else {
        resultsGenerator.writeNull();
      }

      resultsGenerator.writeArrayFieldStart("childIds");
      for (var ci : act.childIds()) resultsGenerator.writeNumber(ci.id());
      resultsGenerator.writeEndArray();

      resultsGenerator.writeStringField("type", act.type());
      resultsGenerator.writeStringField("startOffset", startOffset);
      resultsGenerator.writeFieldName("arguments");
      resultsGenerator.writeTree(activityArgumentsP.unparse(act.arguments()));
      resultsGenerator.writeStringField("startTime", act.start().toString());

      resultsGenerator.writeEndObject();
    }
    resultsGenerator.writeEndArray();

    resultsGenerator.writeEndObject(); // end of spans object
  }

  /** Write the topics section of the results */
  private void writeTopics(JsonGenerator resultsGenerator) throws IOException {
    resultsGenerator.writeStartObject();
    for (var t : results.topics) {
      resultsGenerator.writeObjectFieldStart(t.getMiddle());
      resultsGenerator.writeFieldName("schema");
      resultsGenerator.writeTree(valueSchemaP.unparse(t.getRight()));
      resultsGenerator.writeEndObject();
    }
    resultsGenerator.writeEndObject(); // end of topics object
  }

  /** Write the events section of the results */
  private void writeEvents(JsonGenerator resultsGenerator) throws IOException {
    resultsGenerator.writeStartArray();

    for (var e : results.events.entrySet()) {
      var realTime = e.getKey();
      var transactions = e.getValue();

      int transactionIndex = 0;
      for (var eventGraph : transactions) {
        var flattenedEventGraph = EventGraphFlattener.flatten(eventGraph);

        for (var entry : flattenedEventGraph) {
          var event = entry.getRight();

          resultsGenerator.writeStartObject();
          resultsGenerator.writeStringField("causalTime", entry.getLeft());
          resultsGenerator.writeStringField("realTime", realTime.toString());
          resultsGenerator.writeNumberField("transactionIndex", transactionIndex);
          resultsGenerator.writeFieldName("value");
          resultsGenerator.writeTree(JsonEncoding.encode(event.value()));

          //grab the topic from the event's topic id
          results.topics
              .stream()
              .filter(topic -> topic.getLeft() == event.topicId())
              .findFirst()
              .ifPresent(topic -> {
                try { resultsGenerator.writeStringField("topic", topic.getMiddle()); } catch (IOException ex) { throw new RuntimeException(ex); }
              });

          // optional span id
          resultsGenerator.writeFieldName("spanId");
          event.spanId().ifPresentOrElse(
              spanId -> {
                try { resultsGenerator.writeNumber(spanId); } catch (IOException ex) { throw new RuntimeException(ex); }
              },
              () -> {
                try { resultsGenerator.writeNull(); } catch (IOException ex) { throw new RuntimeException(ex); }
              });

          resultsGenerator.writeEndObject(); // end of event object
        }

        ++transactionIndex;
      }
    }

    resultsGenerator.writeEndArray(); // end of events array
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

topics: [
  "ActivityType.Output.DaemonCheckerSpawner": { //topic name
      schema: ValueSchema
  },
]

events: [
  {
    causalTime : string,
    realTime : Timestamp,
    transactionIndex : int,
    value : {},
    topic: string
    spanId: int,
  }
]
}
 */
