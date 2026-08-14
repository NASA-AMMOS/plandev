package gov.nasa.ammos.plandev.procedural.processor;

/**
 * Export defaults "style" refers to how an exporter's
 * default arguments have been defined within the mission model.
 */
public enum ExportDefaultsStyle {
  AllStaticallyDefined,  // All default arguments provided within @Template static method
  SomeStaticallyDefined, // Some arguments provided within @WithDefaults static class
  NoneDefined            // No default arguments provided
}
