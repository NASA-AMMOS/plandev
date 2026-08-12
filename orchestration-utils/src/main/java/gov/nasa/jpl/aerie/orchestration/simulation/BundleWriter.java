package gov.nasa.jpl.aerie.orchestration.simulation;

import gov.nasa.jpl.aerie.merlin.driver.MissionModel;
import gov.nasa.jpl.aerie.merlin.driver.SimulationResults;
import gov.nasa.jpl.aerie.merlin.protocol.model.DirectiveType;
import gov.nasa.jpl.aerie.merlin.protocol.model.InputType;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.ValueSchema;
import gov.nasa.jpl.aerie.types.ActivityDirective;
import gov.nasa.jpl.aerie.types.ActivityDirectiveId;
import gov.nasa.jpl.aerie.types.Plan;
import gov.nasa.jpl.aerie.types.Timestamp;

import javax.json.Json;
import javax.json.stream.JsonGenerator;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.driver.json.ValueSchemaJsonParser.valueSchemaP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.PostgresParsers.activityArgumentsP;

/**
 * Writes a plan plus one set of simulation results out as a single self-contained "offline bundle" JSON
 * file, matching {@code offline-bundle-schema-v1.json}. Unlike {@link SimulationResultsWriter}'s format
 * (which is Aerie-internal and records everything produced during simulation, e.g. events and topics),
 * a bundle is meant to be consumed with no backend by the Aerie UI's offline loader: it packages the plan's
 * activity directives, per-type parameter schemas sourced from the mission model, and the simulation's
 * spans/resource profiles into one file.
 *
 * <p>Segment and profile serialization is shared with {@link SimulationResultsWriter} via
 * {@link ResourceSegmentJsonWriter}, so the two formats stay consistent without duplicating that logic.</p>
 */
public class BundleWriter {
  private static final String BUNDLE_VERSION = "1.0.0";

  // Write JSONs with Pretty Printing
  private final static Map<String,String> config = Map.of(JsonGenerator.PRETTY_PRINTING, "");

  private final SimulationResults results;
  private final Plan plan;
  private final MissionModel<?> missionModel;
  private final ResourceFileStreamer resourceFileStreamer;

  /**
   * Creates a BundleWriter that will write a bundle for SimulationResults generated
   * using a StreamingResourceManager using the provided ResourceFileStreamer.
   * @param results The SimulationResults to be written
   * @param plan The Plan simulated
   * @param missionModel The MissionModel used to simulate the plan, used to source activityTypes parameter schemas
   * @param rfs The ResourceFileStreamer used during the simulation
   */
  public BundleWriter(SimulationResults results, Plan plan, MissionModel<?> missionModel, ResourceFileStreamer rfs) {
    this.results = results;
    this.plan = plan;
    this.missionModel = missionModel;
    this.resourceFileStreamer = rfs;
  }

  /**
   * Create a BundleWriter that will write a bundle for SimulationResults generated
   * using an InMemorySimulationResourceManager.
   * @param results The SimulationResults to be written
   * @param plan The plan simulated
   * @param missionModel The MissionModel used to simulate the plan
   */
  public BundleWriter(SimulationResults results, Plan plan, MissionModel<?> missionModel) {
    this(results, plan, missionModel, null);
  }

  /**
   * Write the formatted bundle JSON to System.out
   * @param canceledListener The CanceledListener used during simulation.
   *    Used to determine if the results represent a canceled simulation.
   */
  public void writeBundle(CanceledListener canceledListener) {
    try (final var outWriter = new OutputStreamWriter(System.out)) {
      writeBundle(canceledListener, outWriter);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Write the formatted bundle JSON to the specified file.
   * @param canceledListener The CanceledListener used during simulation.
   *    Used to determine if the results represent a canceled simulation.
   * @param outputFilePath The file path to write the bundle to.
   */
  public void writeBundle(CanceledListener canceledListener, Path outputFilePath) {
    try (final var fileWriter = new FileWriter(outputFilePath.toFile())) {
      writeBundle(canceledListener, fileWriter);
      System.out.println("Bundle written to "+outputFilePath);
    } catch (IOException e) {
      throw new RuntimeException("Unable to write to file: "+outputFilePath, e);
    }
  }

  public void writeBundle(CanceledListener canceledListener, Writer outputWriter) {
    try (final var bundleGenerator = Json.createGeneratorFactory(config).createGenerator(outputWriter)) {
      bundleGenerator.writeStartObject();

      bundleGenerator.write("bundleVersion", BUNDLE_VERSION);

      bundleGenerator.writeKey("plan");
      writePlan(bundleGenerator);

      bundleGenerator.writeKey("activityTypes");
      writeActivityTypes(bundleGenerator);

      bundleGenerator.writeKey("activityDirectives");
      writeActivityDirectives(bundleGenerator);

      bundleGenerator.writeKey("simulation");
      writeSimulation(bundleGenerator, canceledListener.get());

      bundleGenerator.writeEnd();
    }
  }

  /** Write the "plan" section of the bundle: {name, startTime, duration}. */
  private void writePlan(JsonGenerator gen) {
    gen.writeStartObject()
       .write("name", plan.name())
       .write("startTime", plan.planStartInstant().toString())
       .write("duration", plan.duration().toString())
       .writeEnd();
  }

  /**
   * Write the "activityTypes" section of the bundle: one entry per directive type known to the mission
   * model, with its parameter schemas (order preserved from the model's declared parameter order),
   * required parameters, and computed-attributes value schema.
   *
   * <p>This is sourced from the loaded {@link MissionModel}'s {@link gov.nasa.jpl.aerie.merlin.driver.DirectiveTypeRegistry}
   * rather than scraped from the results' topics map, because topics don't carry required-parameter
   * information (see the SPIKE note in the WP-1 report for details).</p>
   */
  private void writeActivityTypes(JsonGenerator gen) {
    gen.writeStartArray();

    // Sort by name for deterministic, readable output.
    final var directiveTypes = new TreeMap<String, DirectiveType<?, ?, ?>>(missionModel.getDirectiveTypes().directiveTypes());

    for (final var entry : directiveTypes.entrySet()) {
      final var typeName = entry.getKey();
      final var directiveType = entry.getValue();
      final InputType<?> inputType = directiveType.getInputType();

      gen.writeStartObject();
      gen.write("name", typeName);

      gen.writeKey("parameters");
      gen.writeStartObject();
      final var parameters = inputType.getParameters();
      for (int order = 0; order < parameters.size(); order++) {
        final var parameter = parameters.get(order);
        gen.writeStartObject(parameter.name())
           .write("order", order)
           .write("schema", valueSchemaP.unparse(parameter.schema()))
           .writeEnd();
      }
      gen.writeEnd(); // end of parameters object

      gen.writeStartArray("requiredParameters");
      for (final var requiredParameter : inputType.getRequiredParameters()) {
        gen.write(requiredParameter);
      }
      gen.writeEnd();

      gen.write("computedAttributesValueSchema", valueSchemaP.unparse(directiveType.getOutputType().getSchema()));

      gen.writeEnd(); // end of activity type object
    }

    gen.writeEnd(); // end of activityTypes array
  }

  /** Write the "activityDirectives" section of the bundle. */
  private void writeActivityDirectives(JsonGenerator gen) {
    gen.writeStartArray();

    for (final Map.Entry<ActivityDirectiveId, ActivityDirective> e : plan.activityDirectives().entrySet()) {
      final var id = e.getKey();
      final var directive = e.getValue();

      gen.writeStartObject();
      gen.write("id", id.id());
      gen.write("type", directive.serializedActivity().getTypeName());
      gen.write("startOffset", directive.startOffset().toString());

      gen.writeKey("anchorId");
      if (directive.anchorId() != null) {
        gen.write(directive.anchorId().id());
      } else {
        gen.writeNull();
      }

      gen.write("anchoredToStart", directive.anchoredToStart());
      gen.write("arguments", activityArgumentsP.unparse(directive.serializedActivity().getArguments()));

      gen.writeEnd();
    }

    gen.writeEnd(); // end of activityDirectives array
  }

  /** Write the "simulation" section of the bundle: {simulationStartTime, simulationEndTime, canceled, spans, resources}. */
  private void writeSimulation(JsonGenerator gen, boolean canceled) {
    final var simEndTime = plan.simulationStartTimestamp.plusMicros(results.duration.in(Duration.MICROSECOND));

    gen.writeStartObject();
    gen.write("simulationStartTime", plan.simulationStartTimestamp.toString());
    gen.write("simulationEndTime", simEndTime.toString());
    gen.write("canceled", canceled);

    gen.writeKey("spans");
    writeSpans(gen);

    gen.writeKey("resources");
    writeResources(gen);

    gen.writeEnd(); // end of simulation object
  }

  /** Write the "spans" array of the bundle, merging results' finished and unfinished activities into one list. */
  private void writeSpans(JsonGenerator gen) {
    gen.writeStartArray();

    for (final var e : results.simulatedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      final var startOffset = Duration.of(
          plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())),
          Duration.MICROSECOND
      ).toString();

      gen.writeStartObject();
      gen.write("id", id.id());
      gen.write("type", act.type());

      gen.writeKey("parentId");
      if (act.parentId() != null) {
        gen.write(act.parentId().id());
      } else {
        gen.writeNull();
      }

      gen.writeKey("directiveId");
      act.directiveId().ifPresentOrElse(did -> gen.write(did.id()), gen::writeNull);

      gen.write("startOffset", startOffset);
      gen.write("duration", act.duration().toString());
      gen.write("arguments", activityArgumentsP.unparse(act.arguments()));
      gen.write("attributes", serializedValueP.unparse(act.computedAttributes()));
      gen.writeEnd();
    }

    for (final var e : results.unfinishedActivities.entrySet()) {
      final var id = e.getKey();
      final var act = e.getValue();

      final var startOffset = Duration.of(
          plan.simulationStartTimestamp.microsUntil(new Timestamp(act.start())),
          Duration.MICROSECOND
      ).toString();

      gen.writeStartObject();
      gen.write("id", id.id());
      gen.write("type", act.type());

      gen.writeKey("parentId");
      if (act.parentId() != null) {
        gen.write(act.parentId().id());
      } else {
        gen.writeNull();
      }

      gen.writeKey("directiveId");
      act.directiveId().ifPresentOrElse(did -> gen.write(did.id()), gen::writeNull);

      gen.write("startOffset", startOffset);
      gen.write("arguments", activityArgumentsP.unparse(act.arguments()));
      gen.write("attributes", Json.createObjectBuilder().build());
      gen.writeEnd();
    }

    gen.writeEnd(); // end of spans array
  }

  /** Write the "resources" array of the bundle, merging results' real and discrete profiles into one list. */
  private void writeResources(JsonGenerator gen) {
    gen.writeStartArray();

    for (final var e : results.realProfiles.entrySet()) {
      writeResource(gen, e.getKey(), "real", e.getValue().schema(), () ->
          ResourceSegmentJsonWriter.writeSegments(gen, e.getValue(), e.getKey(), realDynamicsP, resourceFileStreamer));
    }

    for (final var e : results.discreteProfiles.entrySet()) {
      writeResource(gen, e.getKey(), "discrete", e.getValue().schema(), () ->
          ResourceSegmentJsonWriter.writeSegments(gen, e.getValue(), e.getKey(), serializedValueP, resourceFileStreamer));
    }

    gen.writeEnd(); // end of resources array
  }

  private void writeResource(
      JsonGenerator gen,
      String name,
      String type,
      ValueSchema schema,
      Runnable segmentsWriter
  ) {
    gen.writeStartObject()
       .write("name", name)
       .write("type", type)
       .write("schema", valueSchemaP.unparse(schema))
       .writeStartArray("segments");

    segmentsWriter.run();

    gen.writeEnd().writeEnd();
  }
}
