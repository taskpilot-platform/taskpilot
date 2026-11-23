package com.taskpilot.contracts.aiquery.dto;

/** A domain resource reference, never a SQL identifier supplied by a model. */
public record AiResourceRef(String kind, long id) implements Comparable<AiResourceRef> {
    public AiResourceRef {
        if (kind == null || id <= 0) throw new IllegalArgumentException("Invalid resource reference");
    }
    @Override public int compareTo(AiResourceRef other) {
        int type = kind.compareTo(other.kind);
        return type != 0 ? type : Long.compare(id, other.id);
    }
}
