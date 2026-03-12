package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.AuthRequest;
import com.vk.gaming.nexus.entity.User;
import com.vk.gaming.nexus.repository.UserRepository;
import com.vk.gaming.nexus.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*") // Industry standard to allow frontend to talk to backend
public class UserController {

    @Autowired
    private UserRepository userRepository;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Registers the user in PostgreSQL
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest request) {
       try {
           User user = userService.registerUser(request);
           return ResponseEntity.ok(user);
       }
       catch (RuntimeException e) {
           return ResponseEntity.badRequest().body(e.getMessage());
       }

    }

    // Logs the user in and flips is_online to TRUE
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest request) {
        // Note: For a production app, we will add password verification here later!
        try {
            User user = userService.loginUser(request);
            return ResponseEntity.ok(user);
        }
            catch (RuntimeException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

    }

    // Logs the user out and flips is_online to FALSE
    @PostMapping("/logout/{username}")
    public ResponseEntity<?> logout(@PathVariable String username) {
        userService.logoutUser(username);
        return  ResponseEntity.ok(username + "Logged out successfully" );
    }

    // The endpoint your LobbyPanel will call to get active players
    @GetMapping("/lobby")
    public ResponseEntity<List<User>> getLobby() {
        return ResponseEntity.ok(userService.getOnlineUsers());
    }

    @GetMapping("/leaderboard")
    public List<User> getLeaderboard() {
        return userRepository.findTop10ByOrderByWinsDesc();
    }
}
