package com.smartfreight.inbox.service;

/**
 * An operator's first and last name, required when an account is provisioned.
 *
 * <p>The name is customer-facing: {@link ReplySignatureService} signs every reply with the sending
 * operator's name from their token ("Kind regards, Dana Price"). An account created without it falls
 * back to "Customer Service Team", and Keycloak's user profile (firstName/lastName required) stops the
 * user at an "Update Account Information" form on first login (Gate B6).
 */
public record OperatorName(String firstName, String lastName) {

    /** Keycloak's user-profile limit is 255; a signature line stays readable well below that. */
    static final int MAX_LENGTH = 100;

    /**
     * Trims and validates both parts. Characters Keycloak refuses in a person name are left to
     * Keycloak (its rejection is translated by {@link UserManagementService#rejection}).
     *
     * @throws IllegalArgumentException if either part is blank or too long
     */
    public static OperatorName of(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        if (first.isEmpty() || last.isEmpty()) {
            throw new IllegalArgumentException("First and last name are required.");
        }
        if (first.length() > MAX_LENGTH || last.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("First and last name must be at most " + MAX_LENGTH + " characters.");
        }
        return new OperatorName(first, last);
    }
}
