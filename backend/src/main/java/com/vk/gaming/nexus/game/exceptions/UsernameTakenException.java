package com.vk.gaming.nexus.game.exceptions;

public class UsernameTakenException extends NexusBaseException {
    public UsernameTakenException(String username) {
        super("The username '" + username + "' is already taken.");
    }
}
