package gov.nasa.jpl.aerie.orchestration.simulation;

import gov.nasa.jpl.aerie.merlin.driver.resources.AsyncConsumer;
import gov.nasa.jpl.aerie.merlin.driver.resources.ResourceProfiles;

import javax.json.Json;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;

import static gov.nasa.jpl.aerie.merlin.driver.json.SerializedValueJsonParser.serializedValueP;
import static gov.nasa.jpl.aerie.merlin.server.http.ProfileParsers.realDynamicsP;

/**
 * A consumer that writes resource segments to the file system.
 */
public class ResourceFileStreamer implements AsyncConsumer<ResourceProfiles> {
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
          final var s = Json.createObjectBuilder()
                            .add("extent", segment.extent().toString())
                            .add("dynamics", realDynamicsP.unparse(segment.dynamics()))
                            .build();
          fileWriter.write(s.toString()+"\n");
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
          final var s = Json.createObjectBuilder()
                            .add("extent", segment.extent().toString())
                            .add("dynamics", serializedValueP.unparse(segment.dynamics()))
                            .build();
          fileWriter.write(s.toString()+"\n");
        }
        fileWriter.flush();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
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
