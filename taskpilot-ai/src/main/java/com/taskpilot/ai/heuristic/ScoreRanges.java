package com.taskpilot.ai.heuristic;

import java.util.List;

public record ScoreRanges(
        ScoreRange fit,
        ScoreRange load,
        ScoreRange performance) {

    public static ScoreRanges from(List<RawScores> scores) {
        double fitMin = scores.stream().mapToDouble(RawScores::fit).min().orElse(0.0);
        double fitMax = scores.stream().mapToDouble(RawScores::fit).max().orElse(1.0);
        double loadMin = scores.stream().mapToDouble(RawScores::load).min().orElse(0.0);
        double loadMax = scores.stream().mapToDouble(RawScores::load).max().orElse(1.0);
        double perfMin = scores.stream().mapToDouble(RawScores::performance).min().orElse(0.0);
        double perfMax = scores.stream().mapToDouble(RawScores::performance).max().orElse(1.0);

        return new ScoreRanges(
                new ScoreRange(fitMin, fitMax),
                new ScoreRange(loadMin, loadMax),
                new ScoreRange(perfMin, perfMax));
    }
}
