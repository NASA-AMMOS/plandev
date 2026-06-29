package gov.nasa.jpl.aerie.workspace.server;

/**
 * Generates auto-assigned revision names from a per-file revision number using a
 * <a href="https://en.wikipedia.org/wiki/Bijective_numeration">bijective base-26</a> scheme — the same
 * sequence spreadsheets use for column labels:
 *
 * <pre>1→a, 2→b, … 26→z, 27→aa, 28→ab, … 52→az, 53→ba, …</pre>
 *
 * <p>This is the default naming convention ({@code letters_lower}). The resolved name is frozen into the
 * revision at creation time, so changing the convention later never rewrites existing revisions. Other
 * schemes (upper-case, numeric, prefixed) are a later addition; the prototype implements only the default.
 */
public final class RevisionName {
  private RevisionName() {}

  /**
   * @param number the 1-based per-file revision number
   * @return the bijective base-26 name for that number
   * @throws IllegalArgumentException if {@code number < 1}
   */
  public static String forNumber(final int number) {
    if (number < 1) {
      throw new IllegalArgumentException("Revision number must be >= 1, got " + number);
    }
    final var sb = new StringBuilder();
    int n = number;
    while (n > 0) {
      n--; // shift into 0-based so that 'z' rolls over to "aa" rather than "ba"
      sb.append((char) ('a' + (n % 26)));
      n /= 26;
    }
    return sb.reverse().toString();
  }
}
