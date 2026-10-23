package com.studybuddy.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.studybuddy.entity.Admin;
import com.studybuddy.entity.Student;
import com.studybuddy.repository.AdminRepository;
import com.studybuddy.repository.StudentRepository;

/**
 * What Spring Security gets from AuthService at login: the stored hash, the role and
 * whether the account is enabled. Spring Security checks the password itself, so the
 * full login flow is covered in AuthControllerTest.
 * Runs against an empty in-memory H2 (no seeders); each test is rolled back.
 */
@DataJpaTest
@Import(AuthService.class)
class AuthServiceTest {

    @Autowired
    private AuthService authService;
    @Autowired
    private StudentRepository studentRepository;
    @Autowired
    private AdminRepository adminRepository;

    // ACC-6
    @Test
    void loadsStudentWithRoleAndHash() {
        saveStudent("alex@test.com");

        UserDetails user = authService.loadUserByUsername("alex@test.com");

        assertThat(user.getUsername()).isEqualTo("alex@test.com");
        assertThat(user.getPassword()).isEqualTo("hashOfAlex");
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_STUDENT");
        assertThat(user.isEnabled()).isTrue();
    }

    // ACC-6
    @Test
    void loadsAdminWithAdminRole() {
        adminRepository.save(new Admin("admin@test.com", "hashOfAdmin"));

        UserDetails user = authService.loadUserByUsername("admin@test.com");

        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(user.isEnabled()).isTrue();
    }

    // ACC-6
    @Test
    void deactivatedAccountIsDisabled() {
        saveStudent("alex@test.com").setActive(false);

        assertThat(authService.loadUserByUsername("alex@test.com").isEnabled()).isFalse();
    }

    // ACC-6
    @Test
    void unknownEmailIsNotFound() {
        assertThatThrownBy(() -> authService.loadUserByUsername("ghost@test.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    private Student saveStudent(String email) {
        return studentRepository.save(new Student(email, "hashOfAlex", "Alex Tan",
                "School of Computing and Information Systems", "Information Systems", 2, "91234567"));
    }
}
