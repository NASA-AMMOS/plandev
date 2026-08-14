package gov.nasa.jpl.aerie.scheduler.server.exceptions;

/**
 * error whose getMessage() should be reported to a ResultsProtocol.WriterRole.failWith()
 *
 * after reporting to the relevant ResultsProtocol, the exception should be consumed
 *
 * used to allow idiomatic java control flow unwinding and uniform message passing
 * while still differentiating from more serious exceptions that should propagate out
 */
public class ResultsProtocolFailure extends RuntimeException {
  /**
   * create a new exception that should be reported to the ResultsProtocol
   *
   * @param msg the detail of the error that should be reported in the ResultsProtocol
   */
  public ResultsProtocolFailure(String msg) {super(msg);}

  /**
   * create a new exception that should be reported to the ResultsProtocol
   *
   * @param cause the underlying cause of the error, whose own getMessage should be reported
   */
  public ResultsProtocolFailure(Throwable cause) {super(cause.getMessage(), cause);}

  /**
   * create a new exception that should be reported to the ResultsProtocol
   *
   * @param msg the detail of the error that should be reported in the ResultsProtocol
   * @param cause the underlying cause of the error, when known
   */
  public ResultsProtocolFailure(String msg, Throwable cause) {super(msg, cause);}
}
