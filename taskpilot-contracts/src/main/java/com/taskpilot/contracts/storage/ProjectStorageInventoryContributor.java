package com.taskpilot.contracts.storage;

import java.util.List;

/**
 * Implemented by domain/AI modules that own project-scoped external objects
 * so project deletion can inventory all object locators before DB cascades.
 */
public interface ProjectStorageInventoryContributor {

    String sourceType();

    List<StorageObjectLocator> listProjectOwnedObjects(long projectId);
}
