package com.vk.gaming.nexus.exception;

public class EmailAlreadyRegisteredException extends NexusBaseException {
    public EmailAlreadyRegisteredException(String email) {
        super("The email '" + email + "' is already in use.");
    }
}
