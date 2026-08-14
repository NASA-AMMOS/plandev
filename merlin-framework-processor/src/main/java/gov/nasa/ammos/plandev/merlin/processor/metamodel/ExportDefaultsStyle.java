package gov.nasa.jpl.aerie.merlin.processor.metamodel;

/**
 * Export defaults "style" refers to how an exporter's
 * default arguments have been defined within the mission model.
 */
public enum ExportDefaultsStyle {
  AllDefined,            // All default arguments provided within @Parameter annotations
  AllStaticallyDefined,  // All default arguments provided within @Template static method
  SomeStaticallyDefined, // Some arguments provided within @WithDefaults static class
  NoneDefined            // No default arguments provided
}
