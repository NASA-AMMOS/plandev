package gov.nasa.jpl.aerie.orchestration.simulation;

import gov.nasa.jpl.aerie.json.JsonParser;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfile;

import javax.json.Json;
import javax.json.JsonReader;
import javax.json.stream.JsonGenerator;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared helper for writing a resource profile's segments into a JSON generator, inside an
 * already-open array context (ie. between a {@code writeStartArray("segments")}/{@code writeEnd()} pair).
 *
 * <p>Used by both {@link SimulationResultsWriter} (today's results format) and {@link BundleWriter}
 * (the offline bundle format), so the segment-writing logic — including the fast path that streams
 * pre-serialized segments back off disk when a {@link ResourceFileStreamer} was used during
 * simulation — is only defined once.</p>
 */
final class ResourceSegmentJsonWriter {
  private ResourceSegmentJsonWriter() {}

  /**
   * Writes each segment of {@code profile} as a JSON object {@code {"extent": ..., "dynamics": ...}}
   * into the generator's currently-open array.
   *
   * @param resultsGenerator the generator to write to; must currently be inside an open array
   * @param profile the profile whose segments should be written (used when resourceFileStreamer is null)
   * @param profileName the name of the resource, used to look up the streamed temp file
   * @param dynamicsParser parser used to unparse each segment's dynamics value
   * @param resourceFileStreamer if non-null, segments are streamed back from the temp file it wrote
   *   during simulation (and that temp file is deleted once consumed) rather than read from {@code profile}
   */
  static <D> void writeSegments(
      JsonGenerator resultsGenerator,
      ResourceProfile<D> profile,
      String profileName,
      JsonParser<D> dynamicsParser,
      ResourceFileStreamer resourceFileStreamer
  ) {
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
  }
}
