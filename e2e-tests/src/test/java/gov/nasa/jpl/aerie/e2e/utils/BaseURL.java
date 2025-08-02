package gov.nasa.jpl.aerie.e2e.utils;

public enum BaseURL {
  GATEWAY("http://localhost:9000"),
  HASURA("http://localhost:8080"),
  UI("http://localhost"),
  MERLIN_SERVER("http://localhost:27183"),
  SCHEDULER_SERVER("http://localhost:27185"),
  SEQUENCING_SERVER("http://localhost:27184"),
  MERLIN_WORKER_1("http://localhost:27187"),
  MERLIN_WORKER_2("http://localhost:27188"),
  SCHEDULER_WORKER_1("http://localhost:27189"),
  SCHEDULER_WORKER_2("http://localhost:27190"),
  ACTION_SERVER("http://localhost:27186");

  public final String url;
  BaseURL(String url) {
    this.url = url;
  }
}
