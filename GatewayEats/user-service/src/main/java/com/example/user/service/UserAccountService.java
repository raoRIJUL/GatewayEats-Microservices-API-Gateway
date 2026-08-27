package com.example.user.service;

import com.example.user.persistence.Role;
import com.example.user.persistence.UserAccountEntity;
import com.example.user.persistence.UserAccountRepository;
import com.example.user.security.ApplicationUserPrincipal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class UserAccountService implements UserDetailsService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserView register(String name, String email, String password, Role role) {
        String validatedName = validateName(name);
        String normalizedEmail = normalizeAndValidateEmail(email);
        validatePassword(password);
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (repository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException(normalizedEmail);
        }
        try {
            return view(repository.saveAndFlush(new UserAccountEntity(
                    validatedName, normalizedEmail, passwordEncoder.encode(password), role)));
        }
        catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException(normalizedEmail);
        }
    }

    public void ensureUser(String name, String email, String password, Role role) {
        String normalizedEmail = normalizeAndValidateEmail(email);
        if (repository.existsByEmail(normalizedEmail)) {
            return;
        }
        try {
            register(name, normalizedEmail, password, role);
        }
        catch (DuplicateEmailException ignored) {
            // Another user-service instance inserted the same demo user concurrently.
        }
    }

    @Transactional(readOnly = true)
    public UserView getOwnUser(long id, String jwtSubject) {
        requireSameUser(id, jwtSubject);
        return view(repository.findById(id).orElseThrow(() -> new UserNotFoundException(id)));
    }

    @Transactional
    public UserView updateOwnUser(long id, String jwtSubject, String name, String email) {
        requireSameUser(id, jwtSubject);
        UserAccountEntity account = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        account.updateProfile(validateName(name), normalizeAndValidateEmail(email));
        try {
            return view(repository.saveAndFlush(account));
        }
        catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException(account.getEmail());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserAccountEntity account = repository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
        return new ApplicationUserPrincipal(
                account.getId(), account.getName(), account.getEmail(), account.getPasswordHash(),
                account.getRole(), account.isEnabled());
    }

    private void requireSameUser(long id, String subject) {
        if (!Long.toString(id).equals(subject)) {
            throw new AccessDeniedException("You may access only your own user profile");
        }
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 100) {
            throw new IllegalArgumentException("name must contain between 2 and 100 characters");
        }
        return trimmed;
    }

    private String normalizeAndValidateEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized.length() > 254 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("email must be a valid email address");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("password must contain at least 8 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("password must not exceed BCrypt's 72-byte limit");
        }
    }

    private UserView view(UserAccountEntity account) {
        return new UserView(account.getId(), account.getName(), account.getEmail(), account.getRole(),
                account.isEnabled(), account.getCreatedAt(), account.getUpdatedAt());
    }

    public record UserView(long id, String name, String email, Role role, boolean enabled,
                           Instant createdAt, Instant updatedAt) { }
}
