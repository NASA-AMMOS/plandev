package gov.nasa.jpl.aerie.merlin.worker.postgres;

import gov.nasa.jpl.aerie.merlin.driver.Reporter;
import org.apache.commons.lang3.NotImplementedException;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public abstract class ThreadedReporter implements Reporter, AutoCloseable {
  private final BlockingQueue<Request> requestQueue;

  private sealed interface Request {
    record Message(Reporter.Message message) implements Request {}

    record Exit() implements Request {}
  }

  private ThreadedReporter(BlockingQueue<Request> requestQueue) {
    this.requestQueue = requestQueue;
  }

  @Override
  public void report(final Message message) {
    this.requestQueue.add(new Request.Message(message));
  }

  public static ThreadedReporter spawn(final String threadName, final Reporter delegate) {
    final var requestQueue = new ArrayBlockingQueue<Request>(1000);

    final var thread = new Thread(new Worker(requestQueue, delegate));
    thread.setName(threadName);
    thread.start();

    return new ThreadedReporter(requestQueue) {
      @Override
      public void close() {
        requestQueue.add(new Request.Exit());
        try {
          thread.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
  }

  private static final class Worker implements Runnable {
    private final BlockingQueue<ThreadedReporter.Request> requestQueue;
    private final Reporter delegate;

    public Worker(
        final BlockingQueue<Request> requestQueue,
        final Reporter delegate)
    {
      this.requestQueue = Objects.requireNonNull(requestQueue);
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void run() {
      label:
      while (true) {
        try {
          final var request = this.requestQueue.take();

          switch (request) {
            case Request.Message req:
              try {
                this.delegate.report(req.message);
              } catch (final Throwable ex) {
                ex.printStackTrace(System.err);
                // TODO
              }
              break;
            case Request.Exit exit:
              this.delegate.close();
              break label;
          }
        } catch (final Exception ex) {
          ex.printStackTrace(System.err);
          // TODO
        }
      }
    }
  }
}
