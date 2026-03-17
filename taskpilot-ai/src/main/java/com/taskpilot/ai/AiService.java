package com.taskpilot.ai;

import org.springframework.stereotype.Service;

@Service
public class AiService {
    public String status() {
        return "AI Module is Ready!";
    }
}