package gov.nasa.ammos.aerie.procedural.timeline.util

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
