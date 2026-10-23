package com.studybuddy.service.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.User;
import com.studybuddy.exception.LastAdminException;
import com.studybuddy.exception.UserNotFoundException;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.UserRepository;
import com.studybuddy.service.auth.AccountCredentials;

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

    // Email and password rules shared with student sign-up
    private final AccountCredentials credentials;

    public AccountManagementService(UserRepository userRepository,
            MatchRequestRepository matchRequestRepository,
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            AccountCredentials credentials) {
        this.userRepository = userRepository;
        this.matchRequestRepository = matchRequestRepository;
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.credentials = credentials;
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

    // Creates an active Admin account. Student accounts aren't created here: students
    // sign up themselves at /register, since they need profile details an admin
    // wouldn't know (see StudentProfileService.createProfile).
    @Transactional
    public AccountStatus createAdminAccount(String email, String rawPassword) {
        String normalisedEmail = credentials.normaliseEmail(email);
        credentials.checkPassword(rawPassword);
        credentials.requireEmailAvailable(normalisedEmail, null);

        Admin admin = new Admin(normalisedEmail, passwordEncoder.encode(rawPassword));
        return toAccountStatus(adminRepository.save(admin));
    }

    // Changes the email an account logs in with. Keeping the account's current
    // email is allowed (nothing changes); taking another account's email is not.
    @Transactional
    public AccountStatus updateEmail(Long userId, String newEmail) {
        String normalisedEmail = credentials.normaliseEmail(newEmail);
        User user = getUserById(userId);
        credentials.requireEmailAvailable(normalisedEmail, userId);

        user.setEmail(normalisedEmail);
        return toAccountStatus(userRepository.save(user));
    }

    // Sets a new password chosen by the admin. Only the hash is stored.
    @Transactional
    public AccountStatus resetPassword(Long userId, String newRawPassword) {
        credentials.checkPassword(newRawPassword);
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
