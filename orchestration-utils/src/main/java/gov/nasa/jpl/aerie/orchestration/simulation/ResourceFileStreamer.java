package gov.nasa.jpl.aerie.orchestration.simulation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import gov.nasa.jpl.aerie.merlin.driver.engine.ProfileSegment;
import gov.nasa.jpl.aerie.merlin.driver.resources.AsyncConsumer;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfiles;
import gov.nasa.jpl.aerie.merlin.protocol.types.RealDynamics;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;

/**
 * A consumer that writes resource segments to the file system.
 */
public class ResourceFileStreamer implements AsyncConsumer<ResourceProfiles> {
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  private final UUID uuid;
  private final HashMap<String, String> fileNames;

  public ResourceFileStreamer() {
    uuid = UUID.randomUUID();
    fileNames = new HashMap<>();
  }

  /*
    Forbidden Characters for File Names:
    Assuming no nonprintable characters are used, as resource names are already visualized in the UI

    Forbidden on Windows (Linux and Mac use a subset):
      < (less than)
      > (greater than)
      : (colon - sometimes works, but is actually NTFS Alternate Data Streams)
      " (double quote)
      / (forward slash)
      \ (backslash)
      | (vertical bar or pipe)
      ? (question mark)
      * (asterisk)

    Forbidden for Being Potentially Problematic:
      . (period) (windows doesn't allow trailing '.', file extension signifier)
      , (comma)
      + (plus)
      & (ampersand)
      ' (single quote)
      ' ' (space)
  */
  private static final String[] EXCLUSION =  {"<", ">", ",", ":", "\"", "\\\\", "/", "|", "?", "*", ".","+", "&", "'"," "};

  @Override
  public void accept(final ResourceProfiles resourceProfile) {
    for(final var r : resourceProfile.realProfiles().entrySet()) {
      final var name = getFileName(r.getKey());
      try (final var fileWriter = new FileWriter(name, true)) {
        for(final var segment : r.getValue().segments()) {
          fileWriter.write(segmentToJsonString(segment, true));
          fileWriter.write('\n');
        }
        fileWriter.flush();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    for(final var d : resourceProfile.discreteProfiles().entrySet()) {
      final var name = getFileName(d.getKey());
      try (final var fileWriter = new FileWriter(name, true)) {
          for(final var segment : d.getValue().segments()) {
          fileWriter.write(segmentToJsonString(segment, false));
          fileWriter.write('\n');
        }
        fileWriter.flush();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static <D> String segmentToJsonString(final ProfileSegment<D> segment, final boolean isReal) throws IOException {
    final var sw = new StringWriter();
    try (final var gen = JSON_FACTORY.createGenerator(sw)) {
      gen.writeStartObject();
      gen.writeStringField("extent", segment.extent().toString());
      gen.writeFieldName("dynamics");
      if (isReal) {
        final var dynamics = (RealDynamics) segment.dynamics();
        gen.writeStartObject();
        gen.writeNumberField("initial", dynamics.initial);
        gen.writeNumberField("rate", dynamics.rate);
        gen.writeEndObject();
      } else {
        ((SerializedValue) segment.dynamics()).writeTo(gen);
      }
      gen.writeEndObject();
    }
    return sw.toString();
  }

  /**
   * Converts a resource's name into a legal file name and saves it in its cache of filenames.
   */
  public String getFileName(String resourceName) {
    if(fileNames.containsKey(resourceName)) return fileNames.get(resourceName);
    // Create files in the temp directory, or the PWD if there is no set tmpdir
    String dirname = System.getProperty("java.io.tmpdir", ".");
    if(!dirname.endsWith("/")) dirname = dirname + "/"; // Append a Path deliminator if necessary

    final var fileName = dirname + resourceName.replaceAll("[" + Arrays.toString(EXCLUSION) + "]", "_") + uuid.toString()+".rsc";
    fileNames.put(resourceName, fileName);
    return fileName;
  }

  @Override
  public void close() {
    // No-op.
    // ResourceFileStreamer's accept method is self-contained and
    // doesn't leave any state that needs to be cleaned up
  }
}
