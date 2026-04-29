package com.taskpilot.ai.gatekeeper;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface GatekeeperAgent {

    @SystemMessage("""
            You are a strict Intent Classifier for a Project Management system.
            Determine whether the user intends to assign tasks or pick candidates for work.
            If the user intends to assign tasks, find candidates, or check workload for assignment,
            set requiresAHP to true. Otherwise, false.
            Output ONLY valid JSON.
            """)
    IntentResult classify(@UserMessage String userMessage);
}
