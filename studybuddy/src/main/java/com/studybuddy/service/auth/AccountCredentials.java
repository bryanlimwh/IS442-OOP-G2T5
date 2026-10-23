package com.studybuddy.service.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.studybuddy.entity.User;
import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.repository.UserRepository;

/**
 * The rules every login email and password must follow, wherever an account
 * is created or changed: by an admin (AccountManagementService) or by a
 * student signing up (StudentProfileService). Kept in one place so the two
 * can't drift apart.
 */
@Component
public class AccountCredentials {

    private static final int MIN_PASSWORD_LENGTH = 8;
    // BCrypt only uses the first 72 bytes of a password, and Spring Security's
    // BCryptPasswordEncoder refuses anything longer, so it's checked up front.
    private static final int MAX_PASSWORD_BYTES = 72;
    // A basic shape check (something@something.something), not full email validation.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository userRepository;

    public AccountCredentials(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // Trims and lower-cases the email, so "Admin@X.com " and "admin@x.com" can't
    // become two accounts. Lower case also matches how the seeded accounts are stored.
    public String normaliseEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidAccountDetailsException("Enter an email address.");
        }
        String normalised = email.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(normalised).matches()) {
            throw new InvalidAccountDetailsException("Enter a valid email address, like name@example.com.");
        }
        return normalised;
    }

    // Passwords aren't trimmed: spaces in a password may be deliberate.
    public void checkPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidAccountDetailsException(
                    "Passwords must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (rawPassword.isBlank()) {
            throw new InvalidAccountDetailsException("Passwords can't be only spaces.");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidAccountDetailsException("Passwords can be at most 72 characters.");
        }
    }

    // Gives a readable error before the save; the unique constraint on
    // user_account.email still catches two saves racing past this check.
    // userIdKeepingEmail is the account allowed to already have it (the one being
    // updated), or null when creating an account.
    public void requireEmailAvailable(String email, Long userIdKeepingEmail) {
        User existing = userRepository.findByEmail(email);
        if (existing != null && !existing.getUserId().equals(userIdKeepingEmail)) {
            throw new DuplicateEmailException(email);
        }
    }
}
