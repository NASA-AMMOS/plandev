# Updating PlanDev's JNISpice Toolkit

PlanDev currently runs a custom-compiled version of [JNISpice](https://naif.jpl.nasa.gov/pub/naif/misc/JNISpice/) in order to support model-scoped SPICE.
Additionally, PlanDev supports SPICE in M1 Mac and Linux-ARM environments, which are not currently offered by NAIF.

This document goes over the process of updating PlanDev's JNISpice Toolkit.

### Prerequisite Software

**You must be on an M1/M2 Mac**

Aside from that, you will need the following software:

- csh
- lipo
- gcc
- Docker

If you have already set up your machine for PlanDev development then all of these programs should already be installed.

### Step 1: Compiling the JAR and x86 binaries

This step has been entirely automated.

1. Navigate to the ["Create x86 JNISpice Artifacts"](https://github.com/NASA-AMMOS/plandev/actions/workflows/create_jnispice.yml) Action on the PlanDev GitHub.
2. Click "Run Workflow" and run the Workflow against `develop`.
3. When the workflow has finished, navigate to the run's "Summary" page. Download everything under "Artifacts".
4. Extract all the downloaded Artifacts.
5. Empty the contents of [`contrib`'s SPICE resources](../contrib/src/main/resources/gov/nasa/ammos/plandev/spice).
6. Move `JNISpice.dll`, `libJNISpice_Intel.jnilib`, and `libJNISpice_Intel.so` to [`contrib`'s SPICE resources](../contrib/src/main/resources/gov/nasa/ammos/plandev/spice).
7. Move the `JNISpice-N*.jar` into the [`third-party`](../third-party) folder, replacing the existing JAR.
8. Update `contrib`'s [build.gradle](../contrib/build.gradle) to use the new JAR.

### Step 2: Compiling the M1 Mac binary

This step involves removing some Intel-specific compilation and linking flags, as well as removing the
`-flatten_namespace` flag, which is necessary to support model-scoped SPICE.
For more information on the specific flags removed, [see the GCC docs](https://gcc.gnu.org/onlinedocs/gcc/Option-Summary.html)

1. Download and expand the [Mac JNISpice.tar.z from NAIF](https://naif.jpl.nasa.gov/pub/naif/misc/JNISpice/MacIntel_OSX_AppleC_Java1.8_64bit/packages/)
2. Navigate inside the expanded TAR. Open `makeall.csh` your preferred text editor.
   - At the top of the file, add the command `setenv TKLINKOPTIONS "-lm"`
   - Insert the command `setenv TKLINKOPTIONS "-bundle"` directly above the line `echo Creating JNISpice`
   - Insert the command `setenv TKLINKOPTIONS "-lm"` directly above the line `echo Creating brief_c`
3. Open a terminal in the directory and run the following to compile the binary:
   ```bash
   csh
   setenv SOCOMPILEOPTIONS "-c -fPIC -dynamic -fno-common -I../../include -I../tutils_c -isystem. -ansi -Wall -Wundef -Wpointer-arith -Wcast-align -Wsign-compare -D_REENTRANT"
   setenv TKCOMPILER "gcc"
   setenv TKCOMPILEOPTIONS "-c -ansi -O2 -DNON_UNIX_STDIO -Wno-shift-op-parentheses -Wno-logical-op-parentheses -Wno-parentheses"
   setenv LINKLIB "../../lib/tutils_c.a  ../../lib/csupport.a  ../../lib/cspice.a -lm"
   csh makeall.csh
   ```
4. Navigate into the `lib` directory and rename `libJNISpice.jnilib` to `libJNISpice_M1.jnilib`.

### Step 3: Combining the Mac binaries into a universal Mac binary

This step will use `lipo` to combine the Intel and M1 `.jnilib`s into a single `.jnilib` that can be run on any Mac.

1. Move the `libJNISpice_Intel.jnilib` from Step 1 and the `libJNISpice_M1.jnilib` from Step 2 into the same folder.
2. Combine them into a single binary using `lipo`:
   ```bash
   lipo -create libJNISpice_Intel.jnilib libJNISpice_M1.jnilib -output libJNISpice.jnilib
   ```
3. Move `libJNISpice.jnilib` into [`contrib`'s SPICE resources](../contrib/src/main/resources/gov/nasa/ammos/plandev/spice).
   It should now be the only `.jnilib` in the directory.

### Step 4: Compiling the Linux ARM binary

This step uses a Docker container to compile the Linux-ARM binary.

1. Download the [Linux JNISpice.tar.z from NAIF](https://naif.jpl.nasa.gov/pub/naif/misc/JNISpice/PC_Linux_GCC_Java1.8_64bit/packages/)
2. Start an Ubuntu Docker container and open an interactive terminal to it by running:
   ```bash
   docker run --name JNIBuilder --rm -it ubuntu:latest
   ```
3. In a terminal on the host machine, in the directory with `JNISpice.tar.z`, copy the TAR into the container with:
   ```bash
   docker cp JNISpice.tar.z JNIBuilder:/JNISpice.tar.z
   ```
4. Inside the container's terminal, run the following to install the necessary software into the container and expand the TAR:
   ```bash
   apt-get update
   apt-get install -y openjdk-19-jdk gcc csh vim

   uncompress JNISpice.tar
   tar -xvf JNISpice.tar

   cd JNISpice
   csh
   ```
   The `csh` command at the end changes the active terminal from `bash` to `csh`.

5. Open `makeall.csh` in Vim.
   - At the top of the file, add the command `setenv TKLINKOPTIONS "-lm"`
   - Insert the command `setenv TKLINKOPTIONS "-shared"` directly above the line `echo Creating JNISpice`
   - Insert the command `setenv TKLINKOPTIONS "-lm"` directly above the line `echo Creating brief_c`
   - Use `:wq` to exit Vim

   This will ensure that the correct linking arguments are used while building each component.
6. Create the binary:
   ```csh
   setenv SOCOMPILEOPTIONS "-c -fPIC -I../../include -I../tutils_c -isystem. -ansi -Wall -Wundef -Wpointer-arith -Wcast-align -Wsign-compare"
   setenv TKCOMPILEOPTIONS "-c -ansi -O2 -fPIC -DNON_UNIX_STDIO"
   setenv LINKLIB "../../lib/tutils_c.a  ../../lib/csupport.a  ../../lib/cspice.a -lm"
   csh makeall.csh
   ```
7. In a terminal on the host machine, copy the binary onto your machine:
   ```bash
   docker cp JNIBuilder:/JNISpice/lib/libJNISpice.so libJNISpice_M1.so
   ```
8. Move `libJNISpice_M1.so` to [`contrib`'s SPICE resources](../contrib/src/main/resources/gov/nasa/ammos/plandev/spice).


