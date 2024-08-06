package gov.nasa.ammos.aerie.procedural.examples.fooprocedures.constraints;

import gov.nasa.ammos.aerie.procedural.constraints.Constraint;

import java.nio.file.Path;

public class ProcedureLoadingTest {
  void foo() throws ProcedureLoader.ProcedureLoadException {
    var jarPath = "/Users/dailis/projects/aerie/worktrees/develop/procedural/examples/foo-procedures/build/libs/foo-procedures-ConstFruit-constraint.jar";

    // Load jar from absolute filepath
    final Constraint constraint = ProcedureLoader.loadProcedure(Path.of(jarPath), "name", "version");
    // Run code

  }
}
