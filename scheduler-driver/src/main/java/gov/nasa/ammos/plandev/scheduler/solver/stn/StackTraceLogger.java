package gov.nasa.jpl.aerie.scheduler.solver.stn;

import org.slf4j.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Small helper class for logging an exception's stack trace.
 */
public final class StackTraceLogger {
  /**
   * Logs an exception's stack trace at the error level.
   *
   * @param e Exception to log
   * @param l Logger to use
   */
  public static void log(Exception e, Logger l) {
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    l.error(sw.toString());
  }
}
