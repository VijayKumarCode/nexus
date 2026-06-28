package com.vk.gaming.nexus.controller;

import com.vk.gaming.nexus.dto.FeedbackRequest;
import com.vk.gaming.nexus.entity.Feedback;
import com.vk.gaming.nexus.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Slf4j
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<?> submitFeedback(@RequestBody @Valid FeedbackRequest request) {
        try {
            Feedback saved = feedbackService.saveFeedback(
                    request.getUser(),
                    request.getRating(),
                    request.getCategory(),
                    request.getText()
            );
            log.info("Feedback saved — id={} user={}", saved.getId(), saved.getUserName());
            return ResponseEntity.ok(Map.of("message", "Feedback received. Thank you!", "id", saved.getId()));
        } catch (Exception e) {
            log.error("Failed to save feedback", e);
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to save feedback"));
        }
    }

    @GetMapping
    public ResponseEntity<List<Feedback>> getAllFeedback() {
        return ResponseEntity.ok(feedbackService.getAllFeedback());
    }

}

