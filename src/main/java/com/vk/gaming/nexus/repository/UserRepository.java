package com.vk.gaming.nexus.repository;

import com.vk.gaming.nexus.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    List<User> findByStatus(User.UserStatus status);

    // Implementation: Spring Data JPA automatically parses this name
    // to create: SELECT * FROM users ORDER BY wins DESC LIMIT 10
    List<User> findTop10ByOrderByWinsDesc();
}
