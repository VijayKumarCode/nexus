package com.vk.gaming.nexus.exception;

public class UsernameTakenException extends NexusBaseException {
    public UsernameTakenException(String username) {
        super("The username '" + username + "' is already taken.");
    }
}
