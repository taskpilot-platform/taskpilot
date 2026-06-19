package com.taskpilot.contracts.aiquery.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SmartQueryRequestDto {
    private List<QueryChain> chains;

    public SmartQueryRequestDto() {}

    public SmartQueryRequestDto(List<QueryChain> chains) {
        this.chains = chains;
    }

    public List<QueryChain> chains() {
        return chains;
    }

    public List<QueryChain> getChains() {
        return chains;
    }

    public void setChains(List<QueryChain> chains) {
        this.chains = chains;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryChain {
        private List<QueryStep> steps;

        public QueryChain() {}

        public QueryChain(List<QueryStep> steps) {
            this.steps = steps;
        }

        public List<QueryStep> steps() {
            return steps;
        }

        public List<QueryStep> getSteps() {
            return steps;
        }

        public void setSteps(List<QueryStep> steps) {
            this.steps = steps;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryStep {
        private String key;
        private String entity;
        private Map<String, String> filters;
        private Map<String, String> ref;
        private String aggregate;
        private String sort;
        private Integer limit;

        public QueryStep() {}

        public QueryStep(String key, String entity, Map<String, String> filters, Map<String, String> ref, String aggregate, String sort, Integer limit) {
            this.key = key;
            this.entity = entity;
            this.filters = filters;
            this.ref = ref;
            this.aggregate = aggregate;
            this.sort = sort;
            this.limit = limit;
        }

        public String key() { return key; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public String entity() { return entity; }
        public String getEntity() { return entity; }
        public void setEntity(String entity) { this.entity = entity; }

        public Map<String, String> filters() { return filters; }
        public Map<String, String> getFilters() { return filters; }
        public void setFilters(Map<String, String> filters) { this.filters = filters; }

        public Map<String, String> ref() { return ref; }
        public Map<String, String> getRef() { return ref; }
        public void setRef(Map<String, String> ref) { this.ref = ref; }

        public String aggregate() { return aggregate; }
        public String getAggregate() { return aggregate; }
        public void setAggregate(String aggregate) { this.aggregate = aggregate; }

        public String sort() { return sort; }
        public String getSort() { return sort; }
        public void setSort(String sort) { this.sort = sort; }

        public Integer limit() { return limit; }
        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }
    }
}
