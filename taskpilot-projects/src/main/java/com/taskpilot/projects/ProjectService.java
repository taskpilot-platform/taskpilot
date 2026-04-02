package com.taskpilot.projects;

import org.springframework.stereotype.Service;

/**
 * Legacy service - kept for backwards compatibility.
 * Use ProjectServiceImpl in projects.service package for new features.
 */
@Service
public class ProjectService {
    public String status() {
        return "Projects Module is Ready!";
    }
}