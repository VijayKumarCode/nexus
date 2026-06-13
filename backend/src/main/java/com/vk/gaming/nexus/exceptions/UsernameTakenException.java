package com.vk.gaming.nexus.exceptions;

public class UsernameTakenException extends NexusBaseException {
    public UsernameTakenException(String username) {
        super("The username '" + username + "' is already taken.");
    }
}
