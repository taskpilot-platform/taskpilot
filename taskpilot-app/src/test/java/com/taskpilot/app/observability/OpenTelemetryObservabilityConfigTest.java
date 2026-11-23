package com.taskpilot.app.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenTelemetryObservabilityConfigTest {

    @Test
    void filteringSpanExporter_dropsSuccessfulActuatorSpansAndKeepsAppAndErroredSpans() {
        List<SpanData> exported = new ArrayList<>();
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                exported.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };

        var filteringExporter = new OpenTelemetryObservabilityConfig.ActuatorNoiseFilteringSpanExporter(delegate);

        SpanData promScrape = mockSpan("http get /actuator/prometheus", "/actuator/prometheus", StatusData.ok());
        SpanData healthProbe = mockSpan("http head /actuator/health", "/actuator/health", StatusData.unset());
        SpanData failingActuator = mockSpan("http get /actuator/health", "/actuator/health", StatusData.error());
        SpanData aiStream = mockSpan("http post /api/v1/ai/chat/stream", "/api/v1/ai/chat/stream", StatusData.ok());
        SpanData archiveWorker = mockSpan("taskpilot.archive.transfer", null, StatusData.ok());

        CompletableResultCode result = filteringExporter.export(
                List.of(promScrape, healthProbe, failingActuator, aiStream, archiveWorker)
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(exported).containsExactly(failingActuator, aiStream, archiveWorker);
    }

    @Test
    void filteringSpanExporter_skipsDelegateCallWhenAllSpansAreActuatorNoise() {
        int[] delegateCalls = {0};
        SpanExporter delegate = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                delegateCalls[0]++;
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };

        var filteringExporter = new OpenTelemetryObservabilityConfig.ActuatorNoiseFilteringSpanExporter(delegate);
        SpanData promScrape = mockSpan("http get", "/actuator/prometheus", StatusData.ok());

        assertThat(filteringExporter.export(List.of(promScrape)).isSuccess()).isTrue();
        assertThat(delegateCalls[0]).isZero();
    }

    @Test
    void otlpMeterRegistryIsExcludedSoPrometheusRemainsSoleMetricsTransport() {
        boolean otlpRegistryPresent;
        try {
            Class.forName("io.micrometer.registry.otlp.OtlpMeterRegistry");
            otlpRegistryPresent = true;
        } catch (ClassNotFoundException expected) {
            otlpRegistryPresent = false;
        }
        assertThat(otlpRegistryPresent)
                .as("micrometer-registry-otlp must remain excluded so Grafana Cloud Free Tier active series are never doubled")
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void logbackAppenderInstaller_handlesPresentAndMissingOpenTelemetrySafely() {
        OpenTelemetryObservabilityConfig config = new OpenTelemetryObservabilityConfig();

        ObjectProvider<OpenTelemetry> presentProvider = mock(ObjectProvider.class);
        when(presentProvider.getIfAvailable()).thenReturn(OpenTelemetry.noop());
        config.openTelemetryLogbackAppenderInstaller(presentProvider).afterSingletonsInstantiated();

        ObjectProvider<OpenTelemetry> absentProvider = mock(ObjectProvider.class);
        when(absentProvider.getIfAvailable()).thenReturn(null);
        config.openTelemetryLogbackAppenderInstaller(absentProvider).afterSingletonsInstantiated();
    }

    private static SpanData mockSpan(String name, String uri, StatusData status) {
        SpanData span = mock(SpanData.class);
        when(span.getName()).thenReturn(name);
        when(span.getStatus()).thenReturn(status != null ? status : StatusData.create(StatusCode.UNSET, ""));
        Attributes attrs = uri != null
                ? Attributes.of(AttributeKey.stringKey("uri"), uri)
                : Attributes.empty();
        when(span.getAttributes()).thenReturn(attrs);
        return span;
    }
}
