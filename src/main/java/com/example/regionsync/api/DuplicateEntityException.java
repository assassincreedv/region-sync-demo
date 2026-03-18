package com.example.regionsync.api;

/**
 * Thrown when a create or update operation would result in a duplicate entity
 * (e.g. duplicate {@code companyCode}).
 */
public class DuplicateEntityException extends RuntimeException {

    public DuplicateEntityException(String message) {
        super(message);
    }
}
