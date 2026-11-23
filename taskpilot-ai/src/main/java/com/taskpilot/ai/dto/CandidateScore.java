package com.taskpilot.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateScore {
    @JsonProperty("candidateId")
    private Long userId;
    @JsonProperty("displayName")
    private String fullName;
    @JsonIgnore
    private double totalScore;
    private int rank;
    private Double presentationFitValue;
    private String fitStatus;
    private String fitSource;
    private long storedWorkloadValue;
    private String workloadStatus;
    private String workloadUnit;
    private String workloadScope;
    private String workloadMeasuredAt;
    private String performanceStatus;
    private Integer performanceSampleCount;
    private Integer performanceWindowLimit;
    private Double observedQualityMean;
    private Double evidenceWeight;
    private String memberStatus;
}
