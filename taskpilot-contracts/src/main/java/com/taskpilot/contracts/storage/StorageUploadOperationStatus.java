package com.taskpilot.contracts.storage;

/**
 * Durable lifecycle states for an upload reservation and attach operation.
 */
public enum StorageUploadOperationStatus {
    RESERVED,
    PROVIDER_WRITTEN,
    ATTACHED,
    COMPENSATION_QUEUED,
    ABORTED
}
