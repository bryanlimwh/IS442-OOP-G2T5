package com.studybuddy.service.auth;

import java.util.Locale;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.studybuddy.entity.User;
import com.studybuddy.repository.UserRepository;

/**
 * Connects Spring Security's login to our app_user table. At login, Spring
 * calls loadUserByUsername() with the typed email; we return that account's
 * password hash, role and active flag, and Spring checks the password itself.
 */
@Service
public class AuthService implements UserDetailsService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Emails are stored trimmed and lower-cased (AccountCredentials.normaliseEmail), so look up the same form
        String normalised = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(normalised);
        if (user == null) {
            throw new UsernameNotFoundException("No account for " + email);
        }
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                // An account deactivated by an admin is refused at login.
                .disabled(!user.isActive())
                .build();
    }
}
