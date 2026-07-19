package com.fakejobpostsystem.controller;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.repository.InstitutionRepository;
import com.fakejobpostsystem.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final InstitutionRepository institutionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public AuthController(
            UserRepository userRepository,
            InstitutionRepository institutionRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.institutionRepository = institutionRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/login";
    }

    @GetMapping("/signup")
    public String signupPage(Model model) {
        model.addAttribute("institutions", institutionRepository.findAllByOrderByNameAsc());
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(
            @RequestParam("email") String email,
            @RequestParam("password") String password,
            @RequestParam(name = "accountType", defaultValue = "USER") String accountType,
            @RequestParam(name = "institutionId", required = false) Long institutionId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String normalizedEmail = email.trim().toLowerCase();
        if (userRepository.existsByEmail(normalizedEmail)) {
            redirectAttributes.addFlashAttribute("errorMessage", "User already exists");
            return "redirect:/signup";
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(password));
        if ("TPO".equalsIgnoreCase(accountType)) {
            var institution = institutionRepository.findById(institutionId == null ? -1L : institutionId)
                    .orElse(null);
            if (institution == null) {
                redirectAttributes.addFlashAttribute("errorMessage", "Please select a verified institution.");
                return "redirect:/signup";
            }
            String requiredDomain = institution.getVerifiedEmailDomain() == null
                    ? ""
                    : institution.getVerifiedEmailDomain().trim().toLowerCase();
            if (!normalizedEmail.endsWith("@" + requiredDomain)) {
                redirectAttributes.addFlashAttribute("errorMessage", "TPO signup requires an email ending with @" + requiredDomain);
                return "redirect:/signup";
            }
            user.setInstitution(institution);
            user.setRole("ROLE_TPO");
        }
        userRepository.save(user);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(user.getEmail(), password));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        HttpSession session = request.getSession(true);
        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

        redirectAttributes.addFlashAttribute("successMessage", "Account created successfully");
        return "ROLE_TPO".equals(user.getRole()) ? "redirect:/tpo/dashboard" : "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "oauthError", required = false) String oauthError,
            @RequestParam(name = "logout", required = false) String logout,
            Model model) {
        if (error != null) {
            model.addAttribute("errorMessage", "Invalid credentials");
        }
        if (oauthError != null) {
            model.addAttribute("errorMessage", "Google login failed. Check OAuth client ID, client secret, and redirect URI.");
        }
        if (logout != null) {
            model.addAttribute("successMessage", "You have been logged out");
        }
        return "login";
    }

    @GetMapping("/google-login")
    public String googleLogin() {
        return "redirect:/oauth2/authorization/google";
    }
}
