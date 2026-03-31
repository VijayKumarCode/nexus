package com.vk.gaming.nexus.exception;

// A base class for all your game's business errors
public abstract class NexusBaseException extends RuntimeException {
    public NexusBaseException(String message) {
        super(message);
    }
}

