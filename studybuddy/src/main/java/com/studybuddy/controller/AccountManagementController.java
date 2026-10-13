package com.studybuddy.controller;

import java.security.Principal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.studybuddy.exception.DuplicateEmailException;
import com.studybuddy.exception.InvalidAccountDetailsException;
import com.studybuddy.exception.LastAdminException;
import com.studybuddy.exception.UserNotFoundException;
import com.studybuddy.service.admin.AccountManagementService;
import com.studybuddy.service.admin.AccountStatus;

// Lets an admin view every account, add admin accounts, change an account's email
// or password, and deactivate, reactivate or delete an account.
// Only admins reach this page: SecurityConfig restricts /admin/** to the ADMIN role,
// and AccountManagementService checks the role again on every method (@PreAuthorize).
// Each action redirects back to the list with a one-time success or error message
// (Post/Redirect/Get), so refreshing the page doesn't repeat the action.
@Controller
@RequestMapping("/admin/accounts")
public class AccountManagementController {

    private static final String VIEW = "admin/accounts";
    private static final String REDIRECT_TO_LIST = "redirect:/admin/accounts";

    private final AccountManagementService accountManagementService;

    public AccountManagementController(AccountManagementService accountManagementService) {
        this.accountManagementService = accountManagementService;
    }

    @GetMapping
    public String showAccounts(Principal principal, Model model) {
        model.addAttribute("accounts", accountManagementService.getAllUsers());
        // Lets the page mark the logged-in admin's own row
        model.addAttribute("currentEmail", principal.getName());
        return VIEW;
    }

    @PostMapping
    public String createAdminAccount(@RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String password, RedirectAttributes redirectAttributes) {
        try {
            AccountStatus account = accountManagementService.createAdminAccount(email, password);
            redirectAttributes.addFlashAttribute("success",
                    "Admin account " + account.email() + " has been created. It can log in straight away.");
        } catch (InvalidAccountDetailsException | DuplicateEmailException e) {
            // The service's messages are written for the admin, e.g. "Another account already uses ..."
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            // Refill the email so the admin doesn't retype it. The password is never sent back.
            redirectAttributes.addFlashAttribute("newAdminEmail", email);
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{userId}/email")
    public String updateEmail(@PathVariable Long userId, @RequestParam(defaultValue = "") String email,
            RedirectAttributes redirectAttributes) {
        try {
            AccountStatus account = accountManagementService.updateEmail(userId, email);
            redirectAttributes.addFlashAttribute("success",
                    "The account now logs in with " + account.email() + ".");
        } catch (UserNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That account no longer exists.");
        } catch (InvalidAccountDetailsException | DuplicateEmailException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{userId}/password")
    public String resetPassword(@PathVariable Long userId, @RequestParam(defaultValue = "") String password,
            RedirectAttributes redirectAttributes) {
        try {
            AccountStatus account = accountManagementService.resetPassword(userId, password);
            redirectAttributes.addFlashAttribute("success",
                    "The password for " + account.email() + " has been changed.");
        } catch (UserNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That account no longer exists.");
        } catch (InvalidAccountDetailsException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{userId}/deactivate")
    public String deactivateAccount(@PathVariable Long userId, RedirectAttributes redirectAttributes) {
        try {
            AccountStatus account = accountManagementService.deactivateUser(userId);
            redirectAttributes.addFlashAttribute("success",
                    account.email() + " has been deactivated and can no longer log in.");
        } catch (UserNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That account no longer exists.");
        } catch (LastAdminException e) {
            redirectAttributes.addFlashAttribute("error",
                    "This is the last active admin account, so it can't be deactivated.");
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{userId}/reactivate")
    public String reactivateAccount(@PathVariable Long userId, RedirectAttributes redirectAttributes) {
        try {
            AccountStatus account = accountManagementService.reactivateUser(userId);
            redirectAttributes.addFlashAttribute("success", account.email() + " has been reactivated.");
        } catch (UserNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That account no longer exists.");
        }
        return REDIRECT_TO_LIST;
    }

    @PostMapping("/{userId}/delete")
    public String deleteAccount(@PathVariable Long userId, RedirectAttributes redirectAttributes) {
        try {
            accountManagementService.deleteUser(userId);
            redirectAttributes.addFlashAttribute("success", "The account has been deleted.");
        } catch (UserNotFoundException e) {
            redirectAttributes.addFlashAttribute("error", "That account no longer exists.");
        } catch (LastAdminException e) {
            redirectAttributes.addFlashAttribute("error",
                    "This is the last active admin account, so it can't be deleted.");
        } catch (DataIntegrityViolationException e) {
            // The student's match requests, groups or buddy connections still point at the account
            // (see AccountManagementService.deleteUser), so the database refuses the delete.
            redirectAttributes.addFlashAttribute("error",
                    "This student still has match requests, study groups or study buddies, so the account "
                            + "can't be deleted. Deactivate it instead.");
        }
        return REDIRECT_TO_LIST;
    }
}
