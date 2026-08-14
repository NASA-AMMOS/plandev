package gov.nasa.jpl.aerie.stateless.utils;

import java.security.Permission;

/**
 * A SecurityManager that throws an exception when System.exit() is called instead of stopping the JVM.
 */
/*
 SecurityManager is set to be deprecated by JEP 411 (https://openjdk.org/jeps/411)
 However, our usage falls into the niche that currently has no followup:
   - Evaluate whether new APIs or mechanisms are needed to address specific narrow use cases
     for which the Security Manager has been employed, such as blocking System::exit.
 Relevant open JDK ticket: https://bugs.openjdk.org/browse/JDK-8199704
 Once that ticket is closed, this class can be updated to use the new API
*/
@SuppressWarnings("removal")
public class BlockExitSecurityManager extends SecurityManager {
  private static SecurityManager originalSecurityManager;
  private static boolean installed = false;

  private BlockExitSecurityManager(){}

  /**
   * Set a BlockExitSecurityManager as the current Security Manager, if it is not already in use.
   */
  public static void install() {
    if(installed) {
      throw new IllegalStateException("BlockExitSecurityManager is already in use.");
    }

    installed = true;
    originalSecurityManager = System.getSecurityManager();
    System.setSecurityManager(new BlockExitSecurityManager());
  }

  /**
   * If a BlockExitSecurityManager is the current Security Manager,
   * restore the security manager it replaced as the current manager.
   *
   * Note that this method cannot detect if a new SecurityManager was installed between calling `install` and `uninstall`.
   */
  public static void uninstall() {
    if(!installed) {
      throw new IllegalStateException("BlockExitSecurityManager is not in use.");
    }

    installed = false;
    System.setSecurityManager(originalSecurityManager);
  }

  /**
   * Block system exit and instead throw as an exception with the specified status code
   */
  @Override
  public void checkExit(final int statusCode) {
    throw new SystemExit(statusCode);
  }

  /**
   * Defer permission checks to the original security manager, if it exists.
   * Without this override, the JVM hangs during "BlockExitSecurityManager::uninstall"
   */
  @Override
  public void checkPermission(Permission perm) {
    if(originalSecurityManager != null) {
      originalSecurityManager.checkPermission(perm);
    }
  }
}
