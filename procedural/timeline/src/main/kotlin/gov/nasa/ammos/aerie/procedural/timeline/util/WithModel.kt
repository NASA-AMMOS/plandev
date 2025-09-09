package gov.nasa.ammos.aerie.procedural.timeline.util

/**
 * A mixin for accessing a singleton instance of the mission model object.
 *
 * Just make your goal or constraint `implements WithModel<MyModel>`. You can
 * then access the mission model with `this.model()`. This interface makes no
 * guarantees about the simulation configuration the model was instantiated with.
 * It will most likely be the default config, but that is not a formal
 * requirement. The only guarantee is that it will be a valid instance of the class.
 *
 * The type `M` provided to `WithModel<M>` is not checked at compile time.
 * Giving the wrong type will result in a runtime class cast exception when
 * `model()` is called.
 */
interface WithModel<M> {
  @Suppress("unchecked_cast")
  fun model(): M {
    if (modelSingleton == null) {
      throw IllegalStateException("modelSingleton was not initialized.")
    }

    return modelSingleton as M
  }

  companion object {
    @JvmStatic var modelSingleton: Any? = null
  }
}
