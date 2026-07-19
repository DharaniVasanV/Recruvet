package com.fakejobpostsystem.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.repository.UserRepository;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;

    public OAuth2LoginSuccessHandler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OAuth2User oauthUser = (OAuth2User) authentication.getPrincipal();
        String email = OAuth2UserInfo.getEmail(oauthUser);
        if (email == null || email.isBlank()) {
            response.sendRedirect("/login?error");
            return;
        }

        String normalizedEmail = email.toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseGet(User::new);
        user.setEmail(normalizedEmail);
        user.setGoogleId(OAuth2UserInfo.getGoogleId(oauthUser));
        userRepository.save(user);

        response.sendRedirect("/dashboard");
    }
}
