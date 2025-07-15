package gov.nasa.jpl.aerie.merlin.driver.resources;

/**
 * Represents an asynchronous operation that accepts a single input argument and does something
 * with it in the background
 */
public interface AsyncConsumer<T> {
  /**
   * Asynchronously performs this operation on the given argument
   *
   * @param t the input argument
   *
   * accept should not be called after the consumer has been closed
   */
  void accept(T t);

  /**
   * close blocks until the consumer has finished processing all inputs
   *
   * close should be idempotent - i.e. it should be safe to call multiple times
   */
  void close();
}
