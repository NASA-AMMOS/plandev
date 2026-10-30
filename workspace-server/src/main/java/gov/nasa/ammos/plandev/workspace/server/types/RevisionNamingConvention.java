package gov.nasa.ammos.plandev.workspace.server.types;

public enum RevisionNamingConvention {
  ALPHABETICAL {
    // This is equal to 26^6 + 26^5 + 26^4 + 26^3 + 26^2 + 26
    private static final int MAX_ORDINAL = 321272406;

    /**
     * Convert a number to lowercase ASCII letters (1 -> a, 26 -> z, 27 -> aa, ... ).
     * The inverse of {@link #ordinal}
     * @param ordinal the number to be converted. Must be between 1 and {@link #MAX_ORDINAL}, inclusive.
     * @throws IllegalArgumentException if the ordinal is out of bounds for the mapping function
     */
    @Override
    public String name(final int ordinal) throws IllegalArgumentException {
      if (ordinal < 1 || ordinal > MAX_ORDINAL) {
        throw new IllegalArgumentException("Ordinal %d is out of range [1, %d]".formatted(ordinal, MAX_ORDINAL));
      }

      final var name = new StringBuilder();
      for (var n = ordinal; n > 0; n = (n - 1) / 26) {
        name.append((char) ('a' + (n - 1) % 26));
      }

      return name.reverse().toString();
    }

    /**
     * Map a sequence of 6 lowercase ASCII characters to a number ( a -> 1, z -> 26, aa -> 27, ...).
     * The inverse of {@link #name}.
     * @param name the string to be converted. Must contain between 1 and 6 ASCII characters.
     * @throws IllegalArgumentException if the name contains invalid characters,
     *    or an invalid number of valid characters.
     */
    @Override
    public int ordinal(final String name) throws IllegalArgumentException {
      if(name == null) {
        throw new IllegalArgumentException("Parameter 'name' must not be null.");
      }
      // 6 characters is the last length where the ordinal for the "all z's" string is less than Integer.MAX_INT
      // "zzzzzzz" (7 z's) has an ordinal of 8,031,810,176, ~4 times bigger than Integer.MAX_INT
      if (!name.matches("[a-z]{1,6}")) {
        throw new IllegalArgumentException("Invalid revision name \"%s\": expected 1-6 lowercase letters.".formatted(name));
      }
      var ordinal = 0;
      for (final var c : name.toCharArray()) {
        ordinal = ordinal * 26 + (c - 'a' + 1);
      }
      return ordinal;
    }
  },
  NUMERIC {
    /**
     * Stringify an integer
     * @throws IllegalArgumentException if ordinal is less than 1
     */
    @Override
    public String name(final int ordinal) throws IllegalArgumentException {
      if(ordinal < 1) {
        throw new IllegalArgumentException("Parameter 'ordinal' must be at least 1.");
      }
      return String.valueOf(ordinal);
    }

    /**
     * Parse a string into an int.
     * @throws IllegalArgumentException if name is null, if name cannot be parsed into an integer,
     *    or if the integer value of name is less than 1
     */
    @Override
    public int ordinal(final String name) throws IllegalArgumentException {
      if(name == null) {
        throw new IllegalArgumentException("Parameter 'name' must not be null.");
      }

      final int ordinal;
      try {
        ordinal = Integer.parseInt(name);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Parameter 'name' contains invalid data. Expected an integer, received: "+name, e);
      }

      if(ordinal < 1) {
        throw new IllegalArgumentException("Invalid revision '%d': Revisions may not be less than 1.".formatted(ordinal));
      }

      return ordinal;
    }
  };

  public abstract String name(final int ordinal);
  public abstract int ordinal(final String name) throws IllegalArgumentException;

  public static RevisionNamingConvention parseConvention(String name) {
    if (name.equalsIgnoreCase("ALPHABETICAL")) {
      return ALPHABETICAL;
    }
    if (name.equalsIgnoreCase("NUMERIC")) {
      return NUMERIC;
    }
    throw new IllegalArgumentException("Cannot find revision naming convention with name: "+name);
  }
}
