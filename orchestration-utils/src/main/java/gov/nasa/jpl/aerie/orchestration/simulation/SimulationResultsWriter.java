package gov.nasa.jpl.aerie.orchestration.simulation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphFlattener;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.simulationArgumentsP;


public class SimulationResultsWriter {
  private static final double SCHEMA_VERSION = 1;
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

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
    final var printer = new DefaultPrettyPrinter();
    printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

    try (final var gen = JSON_FACTORY.createGenerator(outputWriter)) {
      gen.setPrettyPrinter(printer);

      // Start the top-level object
      gen.writeStartObject();

      // Output the starting information, a set of top-level fields
      writeOpening(gen, canceledListener.get());

      // Write each of the main subsections
      gen.writeFieldName("simulationConfiguration");
      writeSimConfig(gen);

      gen.writeFieldName("profiles");
      writeProfiles(gen);

      gen.writeFieldName("spans");
      writeSpans(gen);

      gen.writeFieldName("topics");
      writeTopics(gen);

      gen.writeFieldName("events");
      writeEvents(gen);

      // End the top-level object
      gen.writeEndObject();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Write the top-level fields of the results JSON */
  private void writeOpening(JsonGenerator gen, boolean canceled) throws IOException {
    final var simEndTime = plan.simulationStartTimestamp.plusMicros(results.duration.in(Duration.MICROSECOND));

    gen.writeNumberField("version", SCHEMA_VERSION);
    gen.writeStringField("simulationStartTime", plan.simulationStartTimestamp.toString());
    gen.writeStringField("simulationEndTime", simEndTime.toString());
    gen.writeBooleanField("canceled", canceled);
  }

  /** Write the simulation configuration section of the results */
  private void writeSimConfig(JsonGenerator gen) throws IOException {
    gen.writeStartObject();
    gen.writeStringField("startTime", plan.simulationStartTimestamp.toString());
    gen.writeStringField("endTime", plan.simulationEndTimestamp.toString());
    gen.writeFieldName("arguments");
    writeJsonValue(gen, simulationArgumentsP.unparse(plan.simulationConfiguration()));
    gen.writeEndObject();
  }

  /**
   * Write the profiles section of the results.
   * Will get profile segments from resourceFileStreamer if it's non-null, or from results' maps otherwise.
   */
  private void writeProfiles(JsonGenerator gen) throws IOException {
    gen.writeStartObject();

    // Each real profile is an object in the array realProfiles
    gen.writeArrayFieldStart("realProfiles");
    for (var e : results.realProfiles.entrySet()) {
      writeRealProfile(gen, e.getKey(), e.getValue());
    }
    gen.writeEndArray();

    // Each discrete profile is an object in the array discreteProfiles
    gen.writeArrayFieldStart("discreteProfiles");
    for (var e : results.discreteProfiles.entrySet()) {
      writeDiscreteProfile(gen, e.getKey(), e.getValue());
    }
    gen.writeEndArray();

    gen.writeEndObject(); // end of profiles object
  }

  /** Write a single real resource profile object */
  private void writeRealProfile(
      JsonGenerator gen,
      String profileName,
      ResourceProfile<RealDynamics> profile
  ) throws IOException {
    gen.writeStartObject();
    gen.writeStringField("name", profileName);
    gen.writeFieldName("schema");
    writeValueSchema(gen, profile.schema());
    gen.writeArrayFieldStart("segments");

    if (resourceFileStreamer != null) {
      writeSegmentsFromFile(gen, profileName);
    } else {
      for (var s : profile.segments()) {
        gen.writeStartObject();
        gen.writeStringField("extent", s.extent().toString());
        gen.writeFieldName("dynamics");
        gen.writeStartObject();
        gen.writeNumberField("initial", s.dynamics().initial);
        gen.writeNumberField("rate", s.dynamics().rate);
        gen.writeEndObject();
        gen.writeEndObject();
      }
    }

    gen.writeEndArray();
    gen.writeEndObject();
  }

  /** Write a single discrete resource profile object */
  private void writeDiscreteProfile(
      JsonGenerator gen,
      String profileName,
      ResourceProfile<SerializedValue> profile
  ) throws IOException {
    gen.writeStartObject();
    gen.writeStringField("name", profileName);
    gen.writeFieldName("schema");
    writeValueSchema(gen, profile.schema());
    gen.writeArrayFieldStart("segments");

    if (resourceFileStreamer != null) {
      writeSegmentsFromFile(gen, profileName);
    } else {
      for (var s : profile.segments()) {
        gen.writeStartObject();
        gen.writeStringField("extent", s.extent().toString());
        gen.writeFieldName("dynamics");
        s.dynamics().writeTo(gen);
        gen.writeEndObject();
      }
    }

    gen.writeEndArray();
    gen.writeEndObject();
  }

  /** Write segments from a resource file streamer temp file */
  private void writeSegmentsFromFile(JsonGenerator gen, String profileName) throws IOException {
    var resourceTempFile = Path.of(resourceFileStreamer.getFileName(profileName));
    try (final BufferedReader reader = Files.newBufferedReader(resourceTempFile)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          gen.writeRawValue(line);
        }
      }
    }
    resourceTempFile.toFile().delete();
  }

  /** Write the spans section of the results file, containing all activity spans */
  private void writeSpans(JsonGenerator gen) throws IOException {
    gen.writeStartObject();

    // Each simulated activity is an object in the array simulatedActivities
    gen.writeArrayFieldStart("simulatedActivities");
    for (var e : results.simulatedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      gen.writeStartObject();

      final var startOffset = Duration.of(plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())), Duration.MICROSECOND).toString();
      final var endTime = act.start().plus(act.duration().in(Duration.MICROSECOND), ChronoUnit.MICROS).toString();

      gen.writeNumberField("id", id.id());

      if (act.directiveId().isPresent()) {
        gen.writeNumberField("directiveId", act.directiveId().get().id());
      } else {
        gen.writeNullField("directiveId");
      }

      if (act.parentId() != null) {
        gen.writeNumberField("parentId", act.parentId().id());
      } else {
        gen.writeNullField("parentId");
      }

      gen.writeArrayFieldStart("childIds");
      for (var ci : act.childIds()) gen.writeNumber(ci.id());
      gen.writeEndArray();

      gen.writeStringField("type", act.type());
      gen.writeStringField("startOffset", startOffset);
      gen.writeStringField("duration", act.duration().toString());

      gen.writeFieldName("attributes");
      act.computedAttributes().writeTo(gen);

      gen.writeFieldName("arguments");
      writeJsonValue(gen, activityArgumentsP.unparse(act.arguments()));

      gen.writeStringField("startTime", act.start().toString());
      gen.writeStringField("endTime", endTime);

      gen.writeEndObject();
    }
    gen.writeEndArray();

    // Each unfinished activity is an object in the array unfinishedActivities
    gen.writeArrayFieldStart("unfinishedActivities");
    for (var e : results.unfinishedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      gen.writeStartObject();

      final var startOffset = Duration.of(plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())), Duration.MICROSECOND).toString();

      gen.writeNumberField("id", id.id());

      if (act.directiveId().isPresent()) {
        gen.writeNumberField("directiveId", act.directiveId().get().id());
      } else {
        gen.writeNullField("directiveId");
      }

      if (act.parentId() != null) {
        gen.writeNumberField("parentId", act.parentId().id());
      } else {
        gen.writeNullField("parentId");
      }

      gen.writeArrayFieldStart("childIds");
      for (var ci : act.childIds()) gen.writeNumber(ci.id());
      gen.writeEndArray();

      gen.writeStringField("type", act.type());
      gen.writeStringField("startOffset", startOffset);

      gen.writeFieldName("arguments");
      writeJsonValue(gen, activityArgumentsP.unparse(act.arguments()));

      gen.writeStringField("startTime", act.start().toString());

      gen.writeEndObject();
    }
    gen.writeEndArray();

    gen.writeEndObject(); // end of spans object
  }

  /** Write the topics section of the results */
  private void writeTopics(JsonGenerator gen) throws IOException {
    gen.writeStartObject();
    for (var t : results.topics) {
      gen.writeObjectFieldStart(t.getMiddle());
      gen.writeFieldName("schema");
      writeValueSchema(gen, t.getRight());
      gen.writeEndObject();
    }
    gen.writeEndObject(); // end of topics object
  }

  /** Write the events section of the results */
  private void writeEvents(JsonGenerator gen) throws IOException {
    gen.writeStartArray();

    for (var e : results.events.entrySet()) {
      var realTime = e.getKey();
      var transactions = e.getValue();

      int transactionIndex = 0;
      for (var eventGraph : transactions) {
        var flattenedEventGraph = EventGraphFlattener.flatten(eventGraph);

        for (var entry : flattenedEventGraph) {
          var event = entry.getRight();

          gen.writeStartObject();
          gen.writeStringField("causalTime", entry.getLeft());
          gen.writeStringField("realTime", realTime.toString());
          gen.writeNumberField("transactionIndex", transactionIndex);
          gen.writeFieldName("value");
          event.value().writeTo(gen);

          //grab the topic from the event's topic id
          results.topics
              .stream()
              .filter(topic -> topic.getLeft() == event.topicId())
              .findFirst()
              .ifPresent(topic -> {
                try {
                  gen.writeStringField("topic", topic.getMiddle());
                } catch (IOException ex) {
                  throw new RuntimeException(ex);
                }
              });

          // optional span id
          if (event.spanId().isPresent()) {
            gen.writeNumberField("spanId", event.spanId().get());
          } else {
            gen.writeNullField("spanId");
          }

          gen.writeEndObject(); // end of event object
        }

        ++transactionIndex;
      }
    }

    gen.writeEndArray(); // end of events array
  }

  /** Write a javax.json JsonValue into a Jackson generator by converting to string */
  private static void writeJsonValue(JsonGenerator gen, javax.json.JsonValue jsonValue) throws IOException {
    gen.writeRawValue(jsonValue.toString());
  }

  /** Write a ValueSchema to a Jackson generator */
  private static void writeValueSchema(JsonGenerator gen, ValueSchema schema) throws IOException {
    writeJsonValue(gen, valueSchemaP.unparse(schema));
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
