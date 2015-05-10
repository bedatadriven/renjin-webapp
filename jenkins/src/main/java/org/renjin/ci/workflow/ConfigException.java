package org.renjin.ci.workflow;

/**
 * Thrown when something is wrong with the configuration of 
 * the build server -- everything should stop!
 */
public class ConfigException extends RuntimeException {

  public ConfigException(String message) {
    super(message);
  }
}
