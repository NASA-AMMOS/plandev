package gov.nasa.ammos.plandev.workspace.server.versioning;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;

import java.time.Instant;

/**
 * A file's most recent content change, derived from history.
 *
 * @param author The author of the commit that last edited the file
 * @param at The time at which the commit was last edited
 * @param commitHash the hash for the commit
 * @param baseline whether this commit is the repository's initial commit
 */
public record LastEdit(PersonIdent author, Instant at, ObjectId commitHash, boolean baseline) {
  public String by() {
    // toExternalString() sanitizes the author's name and email, but adds a timestamp after the email <>
    // as a result, we need to split right after the ">" that closes the email
    return author.toExternalString().split("(?<=>)")[0];
  }
}
