package org.apache.ranger.lakeformation.service;

/**
 * Exception thrown when service definition installation fails.
 */
public class ServiceDefinitionInstallException extends RuntimeException {

    public ServiceDefinitionInstallException(String message) {
        super(message);
    }

    public ServiceDefinitionInstallException(String message, Throwable cause) {
        super(message, cause);
    }
}
