package com.vk.gaming.nexus.service;

import com.vk.gaming.nexus.entity.Feedback;
import com.vk.gaming.nexus.repository.FeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final FeedbackRepository feedbackRepository;

    @Transactional
    public Feedback saveFeedback(String userName, Integer rating, String category, String text) {
        Feedback feedback = Feedback.builder()
                .userName(userName)
                .rating(rating)
                .category(category)
                .text(text)
                .build();
        return feedbackRepository.save(feedback);
    }

    @Transactional(readOnly = true)
    public List<Feedback> getAllFeedback() {
        return feedbackRepository.findAllByOrderByCreatedAtDesc();
    }
}

