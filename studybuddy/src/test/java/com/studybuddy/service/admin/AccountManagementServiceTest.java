package com.studybuddy.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.lang.reflect.RecordComponent;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.AvailabilitySlot;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.MatchRequest;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.StudyBuddyConnection;
import com.studybuddy.entity.StudyGroup;
import com.studybuddy.entity.StudyPreference;
import com.studybuddy.entity.User;
import com.studybuddy.entity.enums.Arrangement;
import com.studybuddy.entity.enums.Role;
import com.studybuddy.entity.enums.StudyGoal;
import com.studybuddy.entity.enums.StudyMode;
import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.exception.LastAdminException;
import com.studybuddy.exception.UserNotFoundException;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.AvailabilitySlotRepository;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyBuddyConnectionRepository;
import com.studybuddy.repository.StudyGroupRepository;
import com.studybuddy.repository.StudyPreferenceRepository;
import com.studybuddy.repository.UserRepository;
import com.studybuddy.service.auth.AccountCredentials;

import jakarta.persistence.EntityManager;

/**
 * AccountManagementService against an empty in-memory H2 (no seeders); each test is rolled back.
 * <p>
 * A plain @DataJpaTest ignores @PreAuthorize, so MethodSecurityConfig below switches method
 * security on. Every test runs as an ADMIN unless it says otherwise.
 * <p>
 * flushAndClear() writes changes to the database and forgets cached objects, so the checks
 * after it read what was really saved. In the app a foreign-key refusal happens when the
 * transaction commits; here userRepository.flush() forces it inside the test.
 */
@DataJpaTest
@Import({ AccountManagementService.class, AccountCredentials.class })
@WithMockUser(username = "admin@test.com", roles = "ADMIN")
class AccountManagementServiceTest {

    private static final String PASSWORD = "password123";
    private static final long UNKNOWN_ID = 999_999L;

    // Turns on @PreAuthorize and gives the service the same BCrypt encoder as SecurityConfig.
    // SecurityConfig itself can't be imported into @DataJpaTest: its filter chains need the web layer.
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }

    @Autowired
    private AccountManagementService service;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private AdminRepository adminRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private StudyPreferenceRepository preferenceRepository;
    @Autowired
    private AvailabilitySlotRepository slotRepository;
    @Autowired
    private StudyGroupRepository studyGroupRepository;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private StudyBuddyConnectionRepository connectionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EntityManager entityManager;

    // ================= Admin only (@PreAuthorize) =================

    // A1, A2, ACC-7, D3
    @Test
    @WithMockUser(username = "student@test.com", roles = "STUDENT")
    void studentIsRefusedByEveryMethod() {
        saveAdmin("admin@test.com");
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        assertRefused(() -> service.getAllUsers());
        assertRefused(() -> service.getUserById(id));
        assertRefused(() -> service.getAccountStatus(id));
        assertRefused(() -> service.deactivateUser(id));
        assertRefused(() -> service.reactivateUser(id));
        assertRefused(() -> service.createAdminAccount("new.admin@test.com", PASSWORD));
        assertRefused(() -> service.updateEmail(id, "new@test.com"));
        assertRefused(() -> service.resetPassword(id, "newpassword1"));
        assertRefused(() -> service.deleteUser(id));

        // The same calls go through for an admin, so the refusals above come from @PreAuthorize
        runAs("ADMIN");
        assertThat(service.getAllUsers()).hasSize(2);
        assertThat(service.getUserById(id).getEmail()).isEqualTo("alex@test.com");
        assertThat(service.getAccountStatus(id).email()).isEqualTo("alex@test.com");
        assertThat(service.deactivateUser(id).active()).isFalse();
        assertThat(service.reactivateUser(id).active()).isTrue();
    }

    // ACC-7, D3
    @Test
    @WithAnonymousUser
    void anonymousIsRefused() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        assertRefused(() -> service.getAllUsers());
        assertRefused(() -> service.deleteUser(id));
    }

    // ACC-7
    @Test
    @WithMockUser(username = "student@test.com", roles = "STUDENT")
    void refusedCallChangesNothing() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");
        Long id = alex.getUserId();
        String hashBefore = alex.getPasswordHash();

        assertRefused(() -> service.deactivateUser(id));
        assertRefused(() -> service.updateEmail(id, "new@test.com"));
        assertRefused(() -> service.resetPassword(id, "newpassword1"));
        assertRefused(() -> service.createAdminAccount("new.admin@test.com", PASSWORD));
        assertRefused(() -> service.deleteUser(id));
        flushAndClear();

        User saved = userRepository.findById(id).orElseThrow();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getEmail()).isEqualTo("alex@test.com");
        assertThat(saved.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    // ================= Viewing accounts =================

    // A2
    @Test
    void getAllUsersListsEveryAccountWithStatus() {
        saveAdmin("admin@test.com");
        saveStudent("alex@test.com", "Alex Tan");
        saveStudent("ben@test.com", "Ben Koh").setActive(false);
        flushAndClear();

        List<AccountStatus> accounts = service.getAllUsers();

        assertThat(accounts)
                .extracting(AccountStatus::email, AccountStatus::displayName, AccountStatus::role, AccountStatus::active)
                .containsExactlyInAnyOrder(
                        tuple("admin@test.com", "Admin: admin@test.com", Role.ADMIN, true),
                        tuple("alex@test.com", "Alex Tan", Role.STUDENT, true),
                        tuple("ben@test.com", "Ben Koh", Role.STUDENT, false));
        assertThat(accounts).allSatisfy(account -> assertThat(account.createdAt()).isNotNull());
    }

    // A2
    @Test
    void studentUsageCountsTheirActivity() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");
        Student ben = saveStudent("ben@test.com", "Ben Koh");
        Student chloe = saveStudent("chloe@test.com", "Chloe Lim");
        Course is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        Course cs201 = courseRepository.save(new Course("CS201", "Data Structures and Algorithms"));
        alex.addCourse(is442);
        alex.addCourse(cs201);
        alex.addPreference(new StudyPreference(is442, StudyMode.ONLINE, Arrangement.EITHER, StudyGoal.EXAM_PREPARATION));
        studyGroupRepository.save(new StudyGroup("IS442 Revision", null, is442,
                StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE, 4, alex));
        matchRequestRepository.save(new MatchRequest(alex, ben, null));
        matchRequestRepository.save(new MatchRequest(ben, alex, null));
        matchRequestRepository.save(new MatchRequest(chloe, alex, null));
        flushAndClear();

        AccountStatus.StudentUsage usage = service.getAccountStatus(alex.getUserId()).usage();

        // courses, preferences, groups led, requests sent, requests received
        assertThat(usage).isEqualTo(new AccountStatus.StudentUsage(2, 1, 1, 1, 2));
    }

    // A2
    @Test
    void adminHasNoUsage() {
        Admin admin = saveAdmin("admin@test.com");

        assertThat(service.getAccountStatus(admin.getUserId()).usage()).isNull();
    }

    // ACC-7
    @Test
    void accountStatusHasNoPasswordHashOrContactNumber() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");

        AccountStatus status = service.getAccountStatus(alex.getUserId());

        assertThat(AccountStatus.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .doesNotContain("passwordHash", "password", "contactNumber");
        assertThat(status.toString()).doesNotContain(alex.getPasswordHash(), "91234567");
    }

    // A1, A2
    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> service.getAccountStatus(UNKNOWN_ID)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.deactivateUser(UNKNOWN_ID)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.reactivateUser(UNKNOWN_ID)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.updateEmail(UNKNOWN_ID, "new@test.com"))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.resetPassword(UNKNOWN_ID, "newpassword1"))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> service.deleteUser(UNKNOWN_ID)).isInstanceOf(UserNotFoundException.class);
    }

    // ================= Creating admin accounts =================

    // A1, ACC-1, ACC-2
    @Test
    void createAdminAccountStoresNormalisedEmailAndHash() {
        AccountStatus created = service.createAdminAccount("  New.Admin@Example.COM ", PASSWORD);
        flushAndClear();

        assertThat(created.email()).isEqualTo("new.admin@example.com");
        assertThat(created.role()).isEqualTo(Role.ADMIN);
        assertThat(created.active()).isTrue();
        assertThat(created.usage()).isNull();
        User saved = userRepository.findByEmail("new.admin@example.com");
        assertThat(saved).isInstanceOf(Admin.class);
        // Only the hash is stored, and login can check the password against it
        assertThat(saved.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
    }

    // A1, ACC-2
    @Test
    void createAdminAccountKeepsSpacesInPassword() {
        service.createAdminAccount("new.admin@test.com", " secret pass ");
        flushAndClear();

        String hash = userRepository.findByEmail("new.admin@test.com").getPasswordHash();
        assertThat(passwordEncoder.matches(" secret pass ", hash)).isTrue();
        assertThat(passwordEncoder.matches("secret pass", hash)).isFalse();
    }

    // A1, ACC-1, ACC-2
    @Test
    void createAdminAccountRejectsBadDetailsAndSavesNothing() {
        assertThatThrownBy(() -> service.createAdminAccount("not-an-email", PASSWORD))
                .isInstanceOf(InvalidAccountDetailsException.class);
        assertThatThrownBy(() -> service.createAdminAccount("new.admin@test.com", "1234567"))
                .isInstanceOf(InvalidAccountDetailsException.class);
        assertThatThrownBy(() -> service.createAdminAccount("new.admin@test.com", "a".repeat(73)))
                .isInstanceOf(InvalidAccountDetailsException.class);
        flushAndClear();

        assertThat(userRepository.count()).isZero();
    }

    // A1, ACC-3
    @Test
    void createAdminAccountRejectsEmailInUseInAnyCase() {
        saveStudent("alex@test.com", "Alex Tan");

        assertThatThrownBy(() -> service.createAdminAccount(" ALEX@Test.com", PASSWORD))
                .isInstanceOf(DuplicateEmailException.class);
        flushAndClear();

        assertThat(adminRepository.count()).isZero();
    }

    // ================= Changing email =================

    // A1, ACC-1
    @Test
    void updateEmailChangesTheLoginEmail() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        AccountStatus updated = service.updateEmail(id, " Alex.Tan@NewMail.com ");
        flushAndClear();

        assertThat(updated.email()).isEqualTo("alex.tan@newmail.com");
        assertThat(userRepository.findByEmail("alex.tan@newmail.com").getUserId()).isEqualTo(id);
        assertThat(userRepository.findByEmail("alex@test.com")).isNull();
    }

    // A1, ACC-3
    @Test
    void updateEmailToOwnEmailIsAllowed() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        assertThat(service.updateEmail(id, "ALEX@test.com").email()).isEqualTo("alex@test.com");
    }

    // A1, ACC-3
    @Test
    void updateEmailToAnotherAccountsEmailChangesNothing() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();
        saveStudent("ben@test.com", "Ben Koh");

        assertThatThrownBy(() -> service.updateEmail(id, "ben@test.com"))
                .isInstanceOf(DuplicateEmailException.class);
        flushAndClear();

        assertThat(userRepository.findById(id).orElseThrow().getEmail()).isEqualTo("alex@test.com");
    }

    // A1, ACC-1
    @Test
    void updateEmailRejectsInvalidEmail() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        assertThatThrownBy(() -> service.updateEmail(id, "alex@"))
                .isInstanceOf(InvalidAccountDetailsException.class);
        assertThatThrownBy(() -> service.updateEmail(id, "  "))
                .isInstanceOf(InvalidAccountDetailsException.class);
        flushAndClear();

        assertThat(userRepository.findById(id).orElseThrow().getEmail()).isEqualTo("alex@test.com");
    }

    // A1, K2: pins current behaviour, update if fixed. A logged-in student is identified by the
    // email in their session, so once it changes the student services no longer find them.
    @Test
    void oldEmailNoLongerFindsStudentAfterUpdate() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        service.updateEmail(id, "alex.new@test.com");
        flushAndClear();

        assertThat(studentRepository.findByEmail("alex@test.com")).isEmpty();
    }

    // ================= Resetting passwords =================

    // A1, ACC-2
    @Test
    void resetPasswordReplacesTheHash() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        service.resetPassword(id, "brand-new-pass");
        flushAndClear();

        String hash = userRepository.findById(id).orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches("brand-new-pass", hash)).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isFalse();
    }

    // A1, ACC-2
    @Test
    void resetPasswordRejectsInvalidPasswordAndKeepsHash() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");
        String hashBefore = alex.getPasswordHash();

        assertThatThrownBy(() -> service.resetPassword(alex.getUserId(), "short"))
                .isInstanceOf(InvalidAccountDetailsException.class);
        assertThatThrownBy(() -> service.resetPassword(alex.getUserId(), "        "))
                .isInstanceOf(InvalidAccountDetailsException.class);
        flushAndClear();

        assertThat(userRepository.findById(alex.getUserId()).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
    }

    // ================= Deactivating and reactivating =================

    // A1, A2
    @Test
    void deactivateAndReactivateStudent() {
        Long id = saveStudent("alex@test.com", "Alex Tan").getUserId();

        assertThat(service.deactivateUser(id).active()).isFalse();
        flushAndClear();
        assertThat(userRepository.findById(id).orElseThrow().isActive()).isFalse();

        assertThat(service.reactivateUser(id).active()).isTrue();
        flushAndClear();
        assertThat(userRepository.findById(id).orElseThrow().isActive()).isTrue();
    }

    // ACC-4
    @Test
    void lastActiveAdminCannotBeDeactivated() {
        Long id = saveAdmin("admin@test.com").getUserId();
        // Students don't count as admins
        saveStudent("alex@test.com", "Alex Tan");

        assertThatThrownBy(() -> service.deactivateUser(id)).isInstanceOf(LastAdminException.class);
        flushAndClear();

        assertThat(userRepository.findById(id).orElseThrow().isActive()).isTrue();
    }

    // ACC-4
    @Test
    void adminCanBeDeactivatedWhileAnotherIsActive() {
        Long id = saveAdmin("admin@test.com").getUserId();
        saveAdmin("second.admin@test.com");

        assertThat(service.deactivateUser(id).active()).isFalse();
    }

    // ACC-4
    @Test
    void inactiveAdminDoesNotCountTowardsLastAdminRule() {
        Long id = saveAdmin("admin@test.com").getUserId();
        saveAdmin("inactive.admin@test.com").setActive(false);

        assertThatThrownBy(() -> service.deactivateUser(id)).isInstanceOf(LastAdminException.class);
    }

    // ================= Deleting =================

    // ACC-4
    @Test
    void lastActiveAdminCannotBeDeleted() {
        Long id = saveAdmin("admin@test.com").getUserId();

        assertThatThrownBy(() -> service.deleteUser(id)).isInstanceOf(LastAdminException.class);
        flushAndClear();

        assertThat(userRepository.findById(id)).isPresent();
    }

    // A1, ACC-4
    @Test
    void adminCanBeDeletedWhileAnotherIsActive() {
        Long id = saveAdmin("admin@test.com").getUserId();
        saveAdmin("second.admin@test.com");

        service.deleteUser(id);
        flushAndClear();

        assertThat(userRepository.findById(id)).isEmpty();
    }

    // A1
    @Test
    void deletingStudentWithoutActivityRemovesTheirPreferences() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");
        Course is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        alex.addCourse(is442);
        StudyPreference preference = new StudyPreference(is442, StudyMode.ONLINE, Arrangement.EITHER,
                StudyGoal.EXAM_PREPARATION);
        preference.addAvailabilitySlot(new AvailabilitySlot(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)));
        alex.addPreference(preference);
        flushAndClear();

        service.deleteUser(alex.getUserId());
        flushAndClear();

        assertThat(userRepository.findById(alex.getUserId())).isEmpty();
        assertThat(preferenceRepository.count()).isZero();
        assertThat(slotRepository.count()).isZero();
        // The course itself stays
        assertThat(courseRepository.count()).isEqualTo(1);
    }

    // A1, ACC-5
    @Test
    void deletingStudentWithMatchRequestFails() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");
        Student ben = saveStudent("ben@test.com", "Ben Koh");
        matchRequestRepository.save(new MatchRequest(alex, ben, "Study together?"));
        flushAndClear();

        service.deleteUser(alex.getUserId());

        assertThatThrownBy(() -> userRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // A1, ACC-5
    @Test
    void deletingGroupLeaderFails() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");
        Course is442 = courseRepository.save(new Course("IS442", "Object Oriented Programming"));
        studyGroupRepository.save(new StudyGroup("IS442 Revision", null, is442,
                StudyGoal.EXAM_PREPARATION, StudyMode.ONLINE, 4, alex));
        flushAndClear();

        service.deleteUser(alex.getUserId());

        assertThatThrownBy(() -> userRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // A1, ACC-5
    @Test
    void deletingStudentWithBuddyFails() {
        Student alex = saveStudent("alex@test.com", "Alex Tan");
        Student ben = saveStudent("ben@test.com", "Ben Koh");
        connectionRepository.save(new StudyBuddyConnection(alex, ben));
        flushAndClear();

        service.deleteUser(alex.getUserId());

        assertThatThrownBy(() -> userRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- Helpers ----------

    private Admin saveAdmin(String email) {
        return adminRepository.save(new Admin(email, passwordEncoder.encode(PASSWORD)));
    }

    private Student saveStudent(String email, String name) {
        return studentRepository.save(new Student(email, passwordEncoder.encode(PASSWORD), name,
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));
    }

    private void assertRefused(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }

    // Swaps the logged-in user part-way through a test
    private void runAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                role.toLowerCase() + "@test.com", null, AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
