package gov.nasa.jpl.aerie.spice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Inspired by:
// https://stackoverflow.com/questions/12036607/bundle-native-dependencies-in-runnable-jar-with-maven
public final class SpiceLoader {
    private SpiceLoader() {}

    public static void loadSpice() {
        try (final InputStream in = SpiceLoader.class.getResourceAsStream(getResourcePathByOS())) {
            if (in == null) throw new RuntimeException("JNISpice native library not found");

            // Copy the JNISpice library to a temporary location on-disk, then load it into the JVM.
            // This needs to be a distinct file from any other load, or else the OS will happily give
            // the JVM a handle to the same previously-loaded image. We want distinct SPICE loads to
            // get their own state, so we can't have that, can we?
            final Path file = Files.createTempFile("JNISpice-", ".tmp");

            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            System.load(file.toString());

            // Unlinking the file from the filesystem won't affect the JVM's reference to the loaded library.
            Files.delete(file);
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String getResourcePathByOS() {
        final String osName = System.getProperty("os.name").toLowerCase();
        final String osArch = System.getProperty("os.arch").toLowerCase();

        if (osName.startsWith("win")) {
            return "JNISpice.dll";
        } else if (osName.startsWith("linux")) {
          if(osArch.startsWith("aarch")||osArch.startsWith("arm")) {    //ARM and AArch refer to the same thing
            return "libJNISpice_M1.so";
          }
          else if(osArch.startsWith("amd")||osArch.startsWith("x86")) { //AMD and x86 are compatible
            return "libJNISpice_Intel.so";
          }
          throw new UnsupportedOperationException("Architecture " +osArch + " is not supported by JNISpice.");
        } else if (osName.startsWith("mac")) {
            return "libJNISpice.jnilib";
        }

        throw new UnsupportedOperationException("Platform " + osName + " is not supported by JNISpice.");
    }
}
