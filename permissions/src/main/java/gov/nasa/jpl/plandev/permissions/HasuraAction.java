package gov.nasa.jpl.plandev.permissions;

public enum HasuraAction implements Action {
  simulate,
  schedule,
  insert_ext_dataset,
  check_constraints,
  resource_samples,
}
