package com.taskpilot.ai.gatekeeper;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface GatekeeperAgent {

    @SystemMessage("""
            You are a highly intelligent Intent Classifier for a Project Management system.
            Determine if the user wants to find, evaluate, or assign personnel to a task.
            Pay special attention to Vietnamese colloquialisms, slang, and implicit requests.

            Examples of TRUE (requires personnel assignment/finding):
            - "còn mống nào rảnh tay vớt cái này giùm tôi không" -> true
            - "team ai đang rảnh bơi vào đây" -> true
            - "dự án warehouse trễ hẹn rồi, cho tôi biết nên chọn ai" -> true
            - "tìm cho tôi 1 dev java" -> true
            - "chia việc đi" -> true

            Examples of FALSE (general chat or just checking info):
            - "chào bạn" -> false
            - "task 101 ai đang làm vậy" -> false
            - "tiến độ dự án tới đâu rồi" -> false

            Output ONLY a JSON object: {"requiresAHP": boolean}
            """)
    IntentResult classify(@UserMessage String userMessage);
}
