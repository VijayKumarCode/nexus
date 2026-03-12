package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.dto.AuthRequest;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // Registers a new user and saves them to PostgreSQL
    public User registerUser( AuthRequest request) {

        Optional<User> existingUser = userRepository.findByUsername(request.getUsername());
        if (existingUser.isPresent()) {
            throw new RuntimeException("Username already exists!");
        }

        User newUser = new User();
        newUser.setUsername(request.getUsername());
        newUser.setPassword(request.getPassword()); //Note: In production, we will encrypt this.
        newUser.setStatus(User.UserStatus.OFFLINE);
        return userRepository.save(newUser);
    }

    public User loginUser(AuthRequest request) {

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found!"));

        if (!user.getPassword().equals(request.getPassword())) {
            throw new RuntimeException("Invalid credentials!");
        }

        // Update status to ONLINE for the Lobby
        user.setStatus(User.UserStatus.ONLINE);
        return userRepository.save(user);
    }

    public void logoutUser(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setStatus(User.UserStatus.OFFLINE);
            userRepository.save(user);
        });
    }

    // Used by LobbyPanel to fetch only active players
    public List<User> getOnlineUsers() {
        return userRepository.findByStatus(User.UserStatus.ONLINE);
    }

    public void handleLogin(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(User.UserStatus.ONLINE);
            u.setIsOnline(true);
            userRepository.save(u);
        });
    }

    public void handleLogout(String username) {
        userRepository.findByUsername(username).ifPresent(u -> {
            u.setStatus(User.UserStatus.OFFLINE);
            u.setIsOnline(false);
            userRepository.save(u);
        });
    }

}
