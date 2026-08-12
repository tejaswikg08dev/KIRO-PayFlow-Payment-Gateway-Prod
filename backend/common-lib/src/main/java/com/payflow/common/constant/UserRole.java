package com.payflow.common.constant;

/**
 * User roles for role-based access control (RBAC).
 */
public enum UserRole {
    USER,      // Regular end-user (customer)
    MERCHANT,  // Merchant who accepts payments
    ADMIN      // Platform administrator
}
