package gov.nasa.ammos.plandev.e2e.bindings.workspace;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the /ws/{workspaceId}/<path> routes.
 *
 * Disabled because the suite is skeletoned but not implemented.
 */
@Disabled
public class SingleItemWorkspaceRoutesTests {
  @Nested
  class Get {
    @Test
    void forbidden() {
      // TODO: Returns a 403 if Forbidden. Will need to temporarily update permissions to actually get this effect
    }

    @Test
    void noSuchFile() {
      // TODO: Returns a 404 if the file specified does not exist
    }

    @Test
    void noSuchWorkspace() {
      // TODO: returns a 404 if the workspace does not exist
    }

    @Test
    void metadataNotPermitted() {
      // TODO: returns a 405 METHOD NOT ALLOWED if a metadata file is passed to the endpoint
    }

    @Test
    void getFile() {
      // TODO: Returns the file's contents
    }

    @Test
    void getDirectoryEmpty() {
      // TODO: Returns a list of the directory's contents
      //  The directory in this test is empty
    }

    @Test
    void getDirectoryAllFileTypes() {
      // TODO: Returns a list of the directory's contents
      //  The directory in this test has one file of each supported content type (including a nested directory)
      //  Once metadata is added, this method should be updated to include the fetched metadata info
    }

    @Test
    void getDirectoryValidDepth() {
      // TODO: pass the depth flag and set it to 1. run it against a folder with a nested folder.
      //  expected result: only the first level is returned
    }

    @Test
    void getDirectoryInvalidDepth() {
      // TODO: Pass invalid values to the depth flag. Should return 400 errors.
    }
  }

  @Nested
  class Put {
    @Test
    void forbidden() {
      // TODO: Returns a 403 if Forbidden. Use viewer role for this
    }

    @Test
    void forbiddenNotOwner() {
      // TODO: Someone who is not the owner cannot put a file in
    }

    @Test
    void noSuchWorkspace() {
      // TODO: Expected 404
    }

    @Test
    void metadataNotPermitted() {
      // TODO: returns a 405 METHOD NOT ALLOWED if a metadata file is passed to the endpoint
    }

    @Test
    void ownerCanPutFile() {
      // TODO: Owner can put a file in the workspace
    }

    @Test
    void collaboratorCanPutFile() {
      // TODO: Collaborator can put a file in the workspace
    }

    @Test
    void ownerCanPutDirectory() {
      // TODO: Owner can put a directory in the workspace
    }

    @Test
    void collaboratorCanPutDirectory() {
      // TODO: Collaborator can put a directory in the workspace
    }

    @Test
    void putCreatesParentDirs() {
      // TODO: When an file's parent directory does not exist,
      //  the ws server automatically creates the parent directory.
    }

    @Test
    void noTypeProvided() {
      // TODO: mandatory query param 'type' is skipped. Expected 400
    }

    @Test
    void invalidTypeProvided() {
      // TODO: mandatory query param 'type' is set to an invalid value. Expected 400
    }

    @Test
    void nameConflictOverwriteFalse() {
      // TODO: If there already exists a file with the same name as the file trying to be set,
      //  and the query param 'overwrite' is set to 'false', the ws server will return 409 Conflicted
      //  and not post the new file (check the file contents)
    }

    @Test
    void overwriteDefaultsToFalse() {
      // TODO: If there already exists a file with the same name as the file trying to be set,
      //  and the query param 'overwrite' is set to 'true', the ws server will return 200 and update the file
      //  (check the file contents)
    }

    @Test
    void nameConflictOverwriteTrue() {
      // TODO: If there already exists a file with the same name as the file trying to be set,
      //  and the query param 'overwrite' is not included, the ws server will return 409 Conflicted
      //  and not post the new file (check the file contents)
    }

    @Test
    void overwriteForbiddenOnDirectory() {
      // TODO: The query param 'overwrite' is forbidden when trying to create a directory. Expected 400
    }

    @Test
    void nameConflictDirectory() {
      // TODO: When the user tries to create a directory that does not exist,
      //  the ws server does nothing and returns 200.
      //  (Put sth in the directory and check its contents before and after)
    }

    @Test
    void cannotPutOutsideOfWorkspace() {
      // TODO: The user cannot put a file outside of the workspace's directory (ie, using ../ or ~/)
    }

    @Test
    void fileNotIncluded() {
      // TODO: No file is attached to the body. Expected 400
    }

    @Test
    void fileAttachedWrongName() {
      // TODO: File is attached, but under the wrong name. Expected 400
    }

    @Test
    void metadataCreatedOnFileCreation() {
      // TODO: metadata file is automatically created on file creation
    }
  }

  @Nested
  class Delete {
    @Test
    void forbidden() {
      // TODO: Returns a 403 if Forbidden. Use viewer role for this
    }

    @Test
    void forbiddenNotOwner() {
      // TODO: Someone who is not the owner cannot delete an item
    }

    @Test
    void noSuchWorkspace() {
      // TODO: Expected 404
    }

    @Test
    void metadataNotPermitted() {
      // TODO: returns a 405 METHOD NOT ALLOWED if a metadata file is passed to the endpoint
    }

    @Test
    void ownerCanDeleteFile() {
      // TODO: Owner can delete a file in the workspace
    }

    @Test
    void collaboratorCanDeleteFile() {
      // TODO: Collaborator can delete a file in the workspace
    }

    @Test
    void ownerCanDeleteDirectory() {
      // TODO: Owner can delete a directory in the workspace
    }

    @Test
    void collaboratorCanDeleteDirectory() {
      // TODO: Collaborator can delete a directory in the workspace
    }

    @Test
    void deleteRecursive() {
      // TODO: Deleting a directory is recursive (removes all content below)
      //  (Notable as this behavior diverges from the default behavior of rm or rmdir, and for the most part
      //    our endpoints follow standard OS behaviors, ie mv and cp)
    }

    @Test
    void deleteIncludesMetadata() {
      // TODO: When a file with a metadata file is deleted, its metadata file is deleted as well.
    }

    @Test
    void cannotDeleteOutsideOfWorkspace() {
      // TODO: A file outside of the workspace cannot be targeted for deletion (ie, using ../ or ~/)
    }
  }

  @Nested
  class Post {
    @Test
    void noMoveOrCopyKey() {
      // TODO: Expected 400 error. Error message should include the endpoint's helptext.
    }

    @Nested
    class Move {
      @Test
      void forbiddenCannotReadSource() {
        // TODO: The role requires the "read_file_directory" permission
        //  (additionally, the user must pass the permission's check for the source ws)
        //    Testing this requires permissions modifications (remember to revert at the end of test)
        //    (to remove the permission from a role and then set the permission to OWNER
        //      (and have the user NOT be the owner of the source ws))
      }

      @Test
      void forbiddenCannotDeleteSource() {
        // TODO: The role requires the "delete_file_directory" permission
        //  (additionally, the user must pass the permission's check for the source ws)
        //  The "viewer" role and the "user" role where the user is not owner/collaborator of the source ws
        //    will test both cases
      }

      @Test
      void forbiddenCannotWriteTarget() {
        // TODO: The role requires the "write_file_directory" permission
        //  (additionally, the user must pass the permission's check for the source ws)
        //  The "viewer" role and the "user" role where the user is not owner/collaborator of the target ws
        //    will test both cases
      }

      @Test
      void withinWSMove() {
            /*
                TODO: Test cases:
                  - Owner
                  - Collaborator
                  - Both (as in the owner is also listed as a collaborator)
                 Expected Results: All cases succeed
             */
      }

      @Test
      void crossWSMove() {
            /*
              TODO: Test cases:
                - owner source, collaborator target
                - owner source,  owner target
                - collaborator source, owner target
                - collaborator source, collaborator target
               Expected Results: All cases succeed
             */
      }

      @Test
      void noSuchSourceWS() {
        // TODO: Expected 404
      }

      @Test
      void noSuchTargetWS() {
        // TODO: Expected 404
      }

      @Test
      void noSuchFile() {
        // TODO: The file trying to be moved does not exist. Expected 404
      }

      @Test
      void noSuchDirectory() {
        // TODO: The directory trying to be moved does not exist. Expected 404
      }

      @Test
      void cannotMoveFileNotInSource() {
        // TODO: Cannot move a file that exists, but is not in the source workspace
      }

      @Test
      void cannotMoveOutsideWSBounds() {
        // TODO: Show that the file cannot be moved to somewhere "outside" of the target workspace's path
      }

      @Test
      void cannotMoveRecursive() {
        // TODO: A directory cannot be moved within itself
      }

      @Test
      void metadataNotPermittedSource() {
        // TODO: returns a 405 METHOD NOT ALLOWED if a metadata file is passed as the entity to move
      }

      @Test
      void metadataNotPermittedTarget() {
        // TODO: returns a 405 METHOD NOT ALLOWED if a metadata file is passed as the destination location
      }

      @Test
      void moveIncludesContents() {
        // TODO: All contents of a directory, including subdirectories, are moved as well
      }

      @Test
      void moveIncludesMetadata() {
        // TODO: When a file with a metadata file is moved, its metadata file is moved as well
      }

      @Test
      void conflictedIfDestinationExists() {
        // TODO: When the destination file exists, the move returns a 409 Conflicted and will not move the file
      }
    }

    @Nested
    class Copy {
      @Test
      void forbiddenCannotReadSource() {
        // TODO: The role requires the "read_file_directory" permission
        //  (additionally, the user must pass the permission's check for the source ws)
        //    Testing this requires permissions modifications (remember to revert at the end of test)
        //    (to remove the permission from a role and then set the permission to OWNER
        //      (and have the user NOT be the owner of the source ws))
      }

      @Test
      void forbiddenCannotWriteTarget() {
        // TODO: The role requires the "write_file_directory" permission
        //  (additionally, the user must pass the permission's check for the source ws)
        //  The "viewer" role and the "user" role where the user is not owner/collaborator of the target ws
        //    will test both cases
      }

      @Test
      void withinWSCopy() {
            /*
                TODO: Test cases:
                  - Owner
                  - Collaborator
                  - Both (as in the owner is also listed as a collaborator)
                 Expected Results: All cases succeed
             */
      }

      @Test
      void crossWSCopy() {
            /*
              TODO: Test cases:
                - owner source, collaborator target
                - owner source,  owner target
                - collaborator source, owner target
                - collaborator source, collaborator target
               Expected Results: All cases succeed
             */
      }

      @Test
      void noSuchSourceWS() {
        // TODO: Expected 404
      }

      @Test
      void noSuchTargetWS() {
        // TODO: Expected 404
      }

      @Test
      void noSuchFile() {
        // TODO: The file trying to be copied does not exist. Expected 404
      }

      @Test
      void noSuchDirectory() {
        // TODO: The directory trying to be copied does not exist. Expected 404
      }

      @Test
      void cannotCopyFileNotInSource() {
        // TODO: Cannot copy a file that exists, but is not in the source workspace
      }

      @Test
      void cannotCopyOutsideWSBounds() {
        // TODO: Show that the file cannot be copied to somewhere "outside" of the target workspace's path
      }

      @Test
      void cannotCopyRecursive() {
        // TODO: A directory cannot be copied within itself
      }

      @Test
      void metadataNotPermittedSource() {
        // TODO: returns a 405 METHOD NOT ALLOWED if a metadata file is passed as the entity to copy
      }

      @Test
      void metadataNotPermittedTarget() {
        // TODO: returns a 405 METHOD NOT ALLOWED if a metadata file is passed as the destination location
      }

      @Test
      void moveIncludesContents() {
        // TODO: All contents of a directory, including subdirectories, are copied as well
      }

      @Test
      void moveIncludesMetadata() {
        // TODO: When a file with a metadata file is copied, its metadata file is copied as well
      }

      @Test
      void conflictedIfDestinationExists() {
        // TODO: When the destination file exists, the endpoint returns a 409 Conflicted and will not copy the file
      }
    }
  }
}
