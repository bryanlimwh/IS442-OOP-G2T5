package com.studybuddy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.Course;
import com.studybuddy.entity.MatchingConfig;
import com.studybuddy.entity.Student;
import com.studybuddy.entity.User;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.AvailabilitySlotRepository;
import com.studybuddy.repository.CourseRepository;
import com.studybuddy.repository.GroupMembershipRepository;
import com.studybuddy.repository.MatchRequestRepository;
import com.studybuddy.repository.MatchingConfigRepository;
import com.studybuddy.repository.StudentRepository;
import com.studybuddy.repository.StudyBuddyConnectionRepository;
import com.studybuddy.repository.StudyGroupRepository;
import com.studybuddy.repository.StudyPreferenceRepository;
import com.studybuddy.repository.UserRepository;

/**
 * USES THE SEEDED DATA: the only test class (with the teammates' MatchingServiceTest,
 * StudentProfileServiceTest and StudyPreferenceServiceTest) that depends on what the seeders
 * create, because the seeders are what it checks. The full application starts, so every seeder
 * runs once; each test is rolled back. The checks are kept to rules that hold whatever the seed
 * data's details are, except exactSeedSizes, which pins today's sizes.
 */
@SpringBootTest
@Transactional
class DataSeedersTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminRepository adminRepository;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private StudyPreferenceRepository studyPreferenceRepository;
    @Autowired
    private AvailabilitySlotRepository availabilitySlotRepository;
    @Autowired
    private MatchingConfigRepository matchingConfigRepository;
    @Autowired
    private StudyGroupRepository studyGroupRepository;
    @Autowired
    private GroupMembershipRepository groupMembershipRepository;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private StudyBuddyConnectionRepository connectionRepository;
    @Autowired
    private MatchingDefaults matchingDefaults;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private List<CommandLineRunner> seeders;

    @Value("${studybuddy.seed.password}")
    private String seedPassword;

    // D1: the brief's minimum amounts of demo data
    @Test
    void atLeastTenCoursesAndFiftyStudentsAreSeeded() {
        assertThat(courseRepository.count()).isGreaterThanOrEqualTo(10);
        assertThat(studentRepository.count()).isGreaterThanOrEqualTo(50);
    }

    // D1: PIN OF TODAY'S SEED SIZES (not a rule): update these numbers if the seed data changes
    @Test
    void exactSeedSizes() {
        assertThat(courseRepository.count()).isEqualTo(10);
        assertThat(studentRepository.count()).isEqualTo(50);
        assertThat(adminRepository.count()).isEqualTo(1);
    }

    // D1, ACC-3, CRS-1: no two accounts share an email, no two courses a code
    @Test
    void emailsAndCourseCodesAreUnique() {
        List<String> emails = userRepository.findAll().stream().map(User::getEmail).toList();
        List<String> codes = courseRepository.findAll().stream().map(Course::getCourseCode).toList();

        assertThat(emails).doesNotHaveDuplicates();
        assertThat(codes).doesNotHaveDuplicates();
    }

    // D1, ACC-2: passwords are stored as BCrypt hashes of studybuddy.seed.password, never as the password
    @Test
    void seededPasswordsAreBcryptHashesOfTheSeedPassword() {
        List<String> hashes = userRepository.findAll().stream().map(User::getPasswordHash).toList();

        assertThat(hashes).allSatisfy(hash -> {
            assertThat(hash).isNotEqualTo(seedPassword);
            assertThat(hash).matches("^\\$2[aby]?\\$\\d{2}\\$.{53}$");
        });
        // Hashing is slow on purpose, so check each different hash once
        assertThat(hashes.stream().distinct()).allSatisfy(hash -> assertThat(passwordEncoder.matches(seedPassword, hash))
                .as("hash matches studybuddy.seed.password").isTrue());
    }

    // D1, ACC-4: one admin account, active, so an admin can log in at the start of a demo
    @Test
    void oneActiveAdminIsSeeded() {
        List<Admin> admins = adminRepository.findAll();

        assertThat(admins).hasSize(1);
        assertThat(admins.get(0).getEmail()).isEqualTo("admin@studybuddy.com");
        assertThat(admins.get(0).isActive()).isTrue();
    }

    // A3, A4, MAT-1: one settings row, equal to the defaults in application.properties
    @Test
    void matchingConfigRowEqualsTheDefaults() {
        List<MatchingConfig> rows = matchingConfigRepository.findAll();

        assertThat(rows).hasSize(1);
        MatchingConfig config = rows.get(0);
        assertThat(config.getStrategy()).isEqualTo(matchingDefaults.strategy());
        assertThat(config.getCourseWeight()).isEqualTo(matchingDefaults.courseWeight());
        assertThat(config.getAvailabilityWeight()).isEqualTo(matchingDefaults.availabilityWeight());
        assertThat(config.getStudyModeWeight()).isEqualTo(matchingDefaults.studyModeWeight());
        assertThat(config.getStudyGoalWeight()).isEqualTo(matchingDefaults.studyGoalWeight());
        assertThat(config.getGroupSizeWeight()).isEqualTo(matchingDefaults.groupSizeWeight());
        assertThat(config.getTargetSessionMinutes()).isEqualTo(matchingDefaults.targetSessionMinutes());
    }

    // D1, PRE-1: a seeded preference is always for a course the student takes
    @Test
    void preferencesAreOnlyForCoursesTheStudentTakes() {
        assertThat(studentRepository.findAll()).allSatisfy(student -> assertThat(student.getPreferences())
                .as(student.getEmail())
                .allSatisfy(preference -> assertThat(student.getCoursesTaken()).contains(preference.getCourse())));
    }

    // D1, PRF-1: contact numbers are valid (8 digits) and nobody shares one
    @Test
    void contactNumbersAreEightDigitsAndUnique() {
        List<String> numbers = studentRepository.findAll().stream().map(Student::getContactNumber).toList();

        assertThat(numbers).allSatisfy(number -> assertThat(number).matches("\\d{8}")).doesNotHaveDuplicates();
    }

    // D1: every seeder skips when its data is already there, so running them again adds nothing
    @Test
    void runningEverySeederAgainChangesNothing() throws Exception {
        Map<String, Long> before = counts();

        assertThat(seeders).hasSizeGreaterThanOrEqualTo(7);
        for (CommandLineRunner seeder : seeders) {
            seeder.run();
        }

        assertThat(counts()).isEqualTo(before);
    }

    private Map<String, Long> counts() {
        Map<String, JpaRepository<?, ?>> repositories = Map.of("users", userRepository, "courses", courseRepository,
                "preferences", studyPreferenceRepository, "slots", availabilitySlotRepository,
                "matching configs", matchingConfigRepository, "groups", studyGroupRepository,
                "memberships", groupMembershipRepository, "match requests", matchRequestRepository,
                "connections", connectionRepository);
        return repositories.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().count()));
    }
}
