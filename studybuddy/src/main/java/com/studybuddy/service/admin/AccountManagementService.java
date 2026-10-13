package com.studybuddy.service.admin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.User;
import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.exception.LastAdminException;
import com.studybuddy.exception.UserNotFoundException;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.UserRepository;

/**
 * System Administrator account management: viewing, creating admin accounts,
 * changing an account's email or password, and deactivating, reactivating and
 * deleting accounts. Works on User, so it covers Student and Admin accounts alike.
 * <p>
 * Status and listing methods return AccountStatus, never the entity, so password
 * hashes and contact numbers are not sent back (see AccountStatus).
 * <p>
 * Class-level {@code @PreAuthorize}: every method here exposes account data
 * (email, status, usage) across all users, not just the delete/deactivate
 * actions, so the whole class — not just the mutating methods — is admin-only.
 * This backs up the {@code /admin/**} URL rule in SecurityConfig; it still
 * applies even if a future caller reaches this service from a non-admin URL.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class AccountManagementService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    // BCrypt only uses the first 72 bytes of a password, and Spring Security's
    // BCryptPasswordEncoder refuses anything longer, so it's checked up front.
    private static final int MAX_PASSWORD_BYTES = 72;
    // A basic shape check (something@something.something), not full email validation.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // Queries the 'user_account' table; with JOINED inheritance Hibernate also
    // joins the 'student' / 'admin' table and returns the right subclass.
    private final UserRepository userRepository;

    // Used to count a student's sent/received match requests for the usage figures
    private final MatchRequestRepository matchRequestRepository;

    // Used to count active admins, so the last one can't be deactivated or deleted
    private final AdminRepository adminRepository;

    // The BCrypt encoder defined in SecurityConfig, the same one the seeders use and
    // login checks against, so passwords set here work at the login page.
    private final PasswordEncoder passwordEncoder;

    public AccountManagementService(UserRepository userRepository,
            MatchRequestRepository matchRequestRepository,
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.matchRequestRepository = matchRequestRepository;
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // readOnly: the student's courses, preferences and groups are lazy collections,
    // so they must be read while the transaction is still open.
    @Transactional(readOnly = true)
    public List<AccountStatus> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toAccountStatus)
                .toList();
    }

    // Returns the entity for use inside the service layer. Controllers should
    // return getAccountStatus() instead, so the password hash is never serialized.
    @Transactional(readOnly = true)
    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Transactional(readOnly = true)
    public AccountStatus getAccountStatus(Long userId) {
        return toAccountStatus(getUserById(userId));
    }

    // A deactivated account is refused at login (see AuthService), but its data is kept.
    @Transactional
    public AccountStatus deactivateUser(Long userId) {
        User user = getUserById(userId);
        if (isLastActiveAdmin(user)) {
            throw new LastAdminException();
        }
        return setActive(userId, false);
    }

    @Transactional
    public AccountStatus reactivateUser(Long userId) {
        return setActive(userId, true);
    }

    // Creates an active Admin account. Student accounts aren't created here: there is
    // no student registration flow yet, and students need profile details an admin
    // wouldn't know.
    @Transactional
    public AccountStatus createAdminAccount(String email, String rawPassword) {
        String normalisedEmail = normaliseEmail(email);
        checkPassword(rawPassword);
        requireEmailAvailable(normalisedEmail, null);

        Admin admin = new Admin(normalisedEmail, passwordEncoder.encode(rawPassword));
        return toAccountStatus(adminRepository.save(admin));
    }

    // Changes the email an account logs in with. Keeping the account's current
    // email is allowed (nothing changes); taking another account's email is not.
    @Transactional
    public AccountStatus updateEmail(Long userId, String newEmail) {
        String normalisedEmail = normaliseEmail(newEmail);
        User user = getUserById(userId);
        requireEmailAvailable(normalisedEmail, userId);

        user.setEmail(normalisedEmail);
        return toAccountStatus(userRepository.save(user));
    }

    // Sets a new password chosen by the admin. Only the hash is stored.
    @Transactional
    public AccountStatus resetPassword(Long userId, String newRawPassword) {
        checkPassword(newRawPassword);
        User user = getUserById(userId);

        user.changePasswordHash(passwordEncoder.encode(newRawPassword));
        return toAccountStatus(userRepository.save(user));
    }

    // Deletes the account row from 'user_account' and its 'student' / 'admin' row.
    // A student's preferences and course links go with it, but match requests,
    // group memberships, groups led and buddy connections still point at the
    // student, so the database rejects the delete (DataIntegrityViolationException)
    // while any of those exist. Deactivate those accounts instead.
    @Transactional
    public void deleteUser(Long userId) {
        User user = getUserById(userId);
        if (isLastActiveAdmin(user)) {
            throw new LastAdminException();
        }
        userRepository.delete(user);
    }

    // True only if the user is an active Admin and no other active admin exists.
    // An already-inactive admin isn't one of the active admins, so removing it is
    // always allowed.
    private boolean isLastActiveAdmin(User user) {
        if (!(user instanceof Admin) || !user.isActive()) {
            return false;
        }
        long activeAdminCount = adminRepository.findAll().stream()
                .filter(User::isActive)
                .count();
        return activeAdminCount <= 1;
    }

    // Trims and lower-cases the email, so "Admin@X.com " and "admin@x.com" can't
    // become two accounts. Lower case also matches how the seeded accounts are stored.
    private String normaliseEmail(String email) {
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
    private void checkPassword(String rawPassword) {
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

    // The user_account table has no unique constraint on email, so this check is
    // what keeps emails unique. userIdKeepingEmail is the account allowed to
    // already have it (the one being updated), or null when creating an account.
    private void requireEmailAvailable(String email, Long userIdKeepingEmail) {
        User existing = userRepository.findByEmail(email);
        if (existing != null && !existing.getUserId().equals(userIdKeepingEmail)) {
            throw new DuplicateEmailException(email);
        }
    }

    private AccountStatus setActive(Long userId, boolean active) {
        User user = getUserById(userId);
        user.setActive(active);
        return toAccountStatus(userRepository.save(user));
    }

    private AccountStatus toAccountStatus(User user) {
        if (user instanceof Student student) {
            int sent = matchRequestRepository.findBySender_userId(student.getUserId()).size();
            int received = matchRequestRepository.findByRecipient_userId(student.getUserId()).size();
            return AccountStatus.from(user, AccountStatus.usageOf(student, sent, received));
        }
        return AccountStatus.from(user, null);
    }
}
