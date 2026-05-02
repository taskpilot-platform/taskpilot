package com.taskpilot.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateScore {
    private Long userId;
    private String fullName;
    private String email;
    private double fitScore;
    private double loadScore;
    private double performanceScore;
    private double confidenceScore;
    private double skillScore;
    private double workloadScore;
    private double totalScore;
    private int currentWorkload;
    private String status;
    private String heuristicMode;
}
