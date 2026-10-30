package com.akshith.payledger.ledger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

// The outbox is published by a scheduler thread, outside the request, so the trace context is saved with
// the row as a W3C traceparent ("00-<trace id>-<span id>-<flags>") and restored when the row is sent.
final class TraceContext {

    private TraceContext() {
    }

    static String current() {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) {
            return null;
        }
        return "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex();
    }

    // A valid but unsampled parent: spans started under it are dropped, which keeps the 500 ms outbox poll
    // from flooding the tracing backend. Requires OTEL_INSTRUMENTATION_SPRING_SCHEDULING_ENABLED=false.
    static Scope unsampled() {
        SpanContext parent = SpanContext.create(TraceId.fromLongs(0, 1), SpanId.fromLong(1),
                TraceFlags.getDefault(), TraceState.getDefault());
        return Context.root().with(Span.wrap(parent)).makeCurrent();
    }

    static Scope restore(String traceparent) {
        if (traceparent == null) {
            return Scope.noop();
        }
        String[] parts = traceparent.split("-");
        SpanContext parent = SpanContext.createFromRemoteParent(parts[1], parts[2],
                TraceFlags.fromHex(parts[3], 0), TraceState.getDefault());
        return Context.root().with(Span.wrap(parent)).makeCurrent();
    }
}
