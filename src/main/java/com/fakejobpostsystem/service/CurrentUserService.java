package com.fakejobpostsystem.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.repository.UserRepository;
import com.fakejobpostsystem.security.OAuth2UserInfo;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User requireUser(Authentication authentication) {
        String email = extractEmail(authentication);
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }

    public String extractEmail(Authentication authentication) {
        if (authentication == null) {
            throw new IllegalStateException("No authenticated user found");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2User) {
            OAuth2User oAuth2User = (OAuth2User) principal;
            String email = OAuth2UserInfo.getEmail(oAuth2User);
            if (email != null && !email.isBlank()) {
                return email.toLowerCase();
            }
        }

        return authentication.getName().toLowerCase();
    }
}
