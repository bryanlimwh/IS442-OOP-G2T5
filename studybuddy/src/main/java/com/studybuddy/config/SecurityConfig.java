package com.studybuddy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.studybuddy.entity.enums.Role;

@Configuration
// Enables @PreAuthorize on service methods (e.g. AccountManagementService),
// as a second check behind the URL-based rules below.
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * DEV ONLY: opens the H2 console. Matches /h2-console/** only, so CSRF and
     * frame options are relaxed for that path and nothing else.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain h2ConsoleFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/h2-console/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable));
        return http.build();
    }

    /**
     * Rules for the rest of the app: the login and sign-up pages, static files and error
     * page are public, /admin/** is for admins only, /matches/** and
     * /groups/** are for students only, and every other page needs a
     * logged-in user. Accounts
     * are looked up by AuthService.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
        .authorizeHttpRequests(auth -> auth
            // Declares which URLs do NOT require authentication
            .requestMatchers("/login", "/register", "/error", "/css/**", "/js/**", "/images/**").permitAll()
            // Declares that URLs with "/admin" as their root
            // can only be accessed by user accounts with the role "ADMIN" 
            .requestMatchers("/admin/**").hasRole(Role.ADMIN.name())
            // Declares that the study buddy matches page can only be accessed by students
            // (an admin has no study preferences to be matched on)
            .requestMatchers("/matches/**").hasRole(Role.STUDENT.name())
            // Declares that the study group pages can only be accessed by students
            // (an admin can't lead or join a group)
            .requestMatchers("/groups/**").hasRole(Role.STUDENT.name())
            // Declares that the match request API can only be accessed by students
            // (an admin has no student profile to send, receive, accept or reject requests with)
            .requestMatchers("/match-requests/**").hasRole(Role.STUDENT.name())
            // Declares that the study buddies page can only be accessed by students
            .requestMatchers("/connections/**").hasRole(Role.STUDENT.name())
            // Declares that other students' public profiles can only be viewed by students
            .requestMatchers("/students/**").hasRole(Role.STUDENT.name())
            // Declares that the profile and study preference pages can only be accessed by students
            // (an admin has no student profile or study preferences)
            .requestMatchers("/student/**").hasRole(Role.STUDENT.name())
            // Declares that any and all other URLs require authentication
            .anyRequest().authenticated()
        )
        
        .formLogin(form -> form
            // Declares the login URL to be "/login"
            // Triggers the Thymeleaf Controller to return the appropriate login HTML
            .loginPage("/login")
            // Specifies that the HTML username login input is named "email"
            .usernameParameter("email")
            // Declares the destination after successful authentication.
            // Setting 'True' means indicates to always redirect to "/home"
            // rather than the page that triggered the login redirect.
            .defaultSuccessUrl("/home", true)
            // Allows unauthenticated access to login-related URLs.
            .permitAll()
        )
        
        .logout(logout -> logout
            .logoutSuccessUrl("/login?logout")
        );

        return http.build();
    }

    /**
     * Hashes passwords before they are stored, and checks a typed password
     * against the stored hash at login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
