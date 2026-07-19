package com.fakejobpostsystem.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.model.Institution;
import com.fakejobpostsystem.model.User;

@Service
public class TpoAccessService {

    private final CurrentUserService currentUserService;

    public TpoAccessService(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    public User requireTpoUser(Authentication authentication) {
        User user = currentUserService.requireUser(authentication);
        if (!"ROLE_TPO".equals(user.getRole()) || user.getInstitution() == null) {
            throw new IllegalStateException("TPO access requires a verified institution account.");
        }
        return user;
    }

    public Institution requireInstitution(Authentication authentication) {
        return requireTpoUser(authentication).getInstitution();
    }
}
