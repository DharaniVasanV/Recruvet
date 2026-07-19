package com.fakejobpostsystem.config;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.fakejobpostsystem.service.CurrentUserService;

@ControllerAdvice(annotations = Controller.class)
public class GlobalControllerAdvice {

    private final CurrentUserService currentUserService;

    public GlobalControllerAdvice(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @ModelAttribute("currentUserEmail")
    public String currentUserEmail(Authentication authentication) {
        if (authentication == null) {
            return null;
        }

        try {
            return currentUserService.extractEmail(authentication);
        } catch (IllegalStateException ex) {
            return null;
        }
    }
}
