package gov.nasa.jpl.aerie.stateless;

import gov.nasa.jpl.aerie.orchestration.simulation.ResourceFileStreamer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class FileNameGeneratorTest {

  @ParameterizedTest
  @ValueSource(chars = {'<', '>', ':', '"', '\\', '/', '|', '?', '*', '.', ',', '+', '&', '\'', ' '})
  public void testFileNameGenerationWithForbiddenChars(char c) {
    final var resourceName = c+"This"+c+"name"+c+"has"+c+"forbidden"+c+"chars";
    final var expectedFileName = "_This_name_has_forbidden_chars";

    assertTrue(new ResourceFileStreamer().getFileName(resourceName).contains(expectedFileName));

  }

  @Test
  public void testFileNameGenerationRandomForbiddenChars() {
    final char[] forbiddenChars = {'<', '>', ':', '"', '\\', '/', '|', '?', '*', '.', ',', '+', '&', '\'', ' '};
    final var fileGenerator = new ResourceFileStreamer();

    final var random = new Random();
    for (int i = 0; i < 1000; i++) {
      final var resourceNameBuilder = new StringBuilder();
      final var expectedFileNameBuilder = new StringBuilder();

      for (int w = 0; w < 5; w++) { // 5 words
        final var randomChar = forbiddenChars[random.nextInt(forbiddenChars.length)];
        resourceNameBuilder.append(randomChar).append("hello").append(randomChar);
        expectedFileNameBuilder.append("_").append("hello").append("_");
      }
      resourceNameBuilder.append("world");
      expectedFileNameBuilder.append("world");

      assertTrue(fileGenerator.getFileName(resourceNameBuilder.toString()).contains(expectedFileNameBuilder.toString()));
    }
  }
}
