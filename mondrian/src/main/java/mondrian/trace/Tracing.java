/*
 * This software is subject to the terms of the Eclipse Public License v1.0
 * Agreement, available at the following URL:
 * http://www.eclipse.org/legal/epl-v10.html.
 * You must accept the terms of that agreement to use this software.
 */
package mondrian.trace;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * OpenTelemetry tracing for one request: the XML/A Execute or MCP tool call at the root, the
 * DAX/MDX parse, the MDX execution and every SQL statement below it.
 *
 * <p><b>Off unless an endpoint is configured</b> ({@code emondrian.trace.endpoint}, or the
 * standard {@code OTEL_EXPORTER_OTLP_ENDPOINT}): every method then returns {@link #NOOP} at the
 * cost of one volatile read, and nothing touches the MDC.
 *
 * <p><b>How a span reaches the SQL threads.</b> The current span's ids are kept in log4j's
 * {@link ThreadContext} (keys {@code trace_id}, {@code span_id}, {@code trace_flags}), not only in
 * OpenTelemetry's own {@code Context}. Mondrian already snapshots the ThreadContext when it creates
 * an {@code Execution} and restores it on every thread that works for that execution -- the
 * result shepherd, the segment loader, the segment cache actor -- so a span started on any of them
 * finds its parent there, across the actor-queue handoffs that thread-local context propagation
 * would lose. It also puts {@code trace_id} into any log line whose pattern asks for it.
 *
 * <p><b>Never a reason for a query to fail or wait.</b> Spans go to a bounded batch processor
 * that drops when full; exporter failures are the SDK's to log, and any exception from this class's
 * own code is swallowed.
 *
 * <p>Settings (system properties; environment variables in brackets):
 * <ul>
 *   <li>{@code emondrian.trace.endpoint} [OTEL_EXPORTER_OTLP_ENDPOINT] -- OTLP/HTTP base URL,
 *       e.g. {@code http://alloy:4318}; empty = off.
 *   <li>{@code emondrian.trace.sample} -- ratio of new traces kept, 0..1, default 1.
 *   <li>{@code emondrian.trace.text} -- {@code on} (default) or {@code off}: whether MDX and SQL
 *       text go into span attributes (redacted, capped at 8 KB).
 *   <li>{@code emondrian.trace.service} [OTEL_SERVICE_NAME] -- default {@code emondrian}.
 *   <li>{@code emondrian.trace.queue} -- span queue size, default 4096.
 * </ul>
 */
public final class Tracing {

  private static final Logger LOGGER = LogManager.getLogger( Tracing.class );

  public static final String TRACE_ID = "trace_id";
  public static final String SPAN_ID = "span_id";
  public static final String TRACE_FLAGS = "trace_flags";

  /** Longest text attribute; MDX and SQL beyond this are cut by the SDK's span limits. */
  private static final int MAX_TEXT = 8192;

  private static volatile Tracer tracer;
  private static volatile boolean text = true;
  private static volatile OpenTelemetrySdk sdk;

  static {
    try {
      init();
    } catch ( Throwable t ) {
      LOGGER.warn( "Tracing disabled: " + t );
      tracer = null;
    }
  }

  private Tracing() {
  }

  private static void init() {
    String endpoint = setting( "emondrian.trace.endpoint", "OTEL_EXPORTER_OTLP_ENDPOINT", "" );
    if ( endpoint.isEmpty() ) {
      LOGGER.info( "Tracing off (no emondrian.trace.endpoint / OTEL_EXPORTER_OTLP_ENDPOINT)" );
      return;
    }
    double ratio = 1.0;
    try {
      ratio = Double.parseDouble( setting( "emondrian.trace.sample", null, "1" ) );
    } catch ( NumberFormatException e ) {
      // keep 1
    }
    text = !"off".equalsIgnoreCase( setting( "emondrian.trace.text", null, "on" ) );
    String service = setting( "emondrian.trace.service", "OTEL_SERVICE_NAME", "emondrian" );
    int queue = 4096;
    try {
      queue = Integer.parseInt( setting( "emondrian.trace.queue", null, "4096" ) );
    } catch ( NumberFormatException e ) {
      // keep default
    }
    String url = endpoint.endsWith( "/v1/traces" ) ? endpoint
      : endpoint.replaceAll( "/+$", "" ) + "/v1/traces";
    OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
      .setEndpoint( url )
      .setTimeout( Duration.ofSeconds( 5 ) )
      .build();
    SdkTracerProvider provider = SdkTracerProvider.builder()
      .setResource( Resource.getDefault().merge( Resource.create(
        Attributes.of( AttributeKey.stringKey( "service.name" ), service ) ) ) )
      .setSampler( Sampler.parentBased( Sampler.traceIdRatioBased( Math.max( 0, Math.min( 1, ratio ) ) ) ) )
      .setSpanLimits( SpanLimits.builder().setMaxAttributeValueLength( MAX_TEXT ).build() )
      .addSpanProcessor( BatchSpanProcessor.builder( exporter )
        .setMaxQueueSize( Math.max( 64, queue ) )
        .setScheduleDelay( Duration.ofSeconds( 2 ) )
        .build() )
      .build();
    sdk = OpenTelemetrySdk.builder().setTracerProvider( provider ).build();
    tracer = sdk.getTracer( "mondrian" );
    LOGGER.info( "Tracing on: " + url + ", sample " + ratio + ", text " + ( text ? "on" : "off" )
      + ", service " + service );
  }

  private static String setting( String property, String env, String fallback ) {
    String v = System.getProperty( property );
    if ( ( v == null || v.isBlank() ) && env != null ) {
      v = System.getenv( env );
    }
    return v == null || v.isBlank() ? fallback : v.trim();
  }

  /** Whether spans are being recorded at all. */
  public static boolean isEnabled() {
    return tracer != null;
  }

  /** Whether statement text (MDX, SQL) may go into spans. */
  public static boolean includeText() {
    return text;
  }

  /** Flushes and stops the exporter; for webapp shutdown. */
  public static void shutdown() {
    OpenTelemetrySdk s = sdk;
    tracer = null;
    if ( s != null ) {
      try {
        s.getSdkTracerProvider().shutdown();
      } catch ( Throwable ignored ) {
        // shutting down
      }
    }
  }

  /**
   * Starts a span as a child of the span named in the ThreadContext; without one, nothing is
   * recorded (only a root span starts a trace). With {@code current} true it becomes the current span: its ids replace the
   * ThreadContext's until {@link SpanHandle#end()}, so spans started below it -- on this thread, or
   * on threads that copy this thread's ThreadContext later -- nest under it.
   */
  public static SpanHandle start( String name, boolean current ) {
    return start( name, current, false );
  }

  /** As {@link #start(String, boolean)}; {@code root} ignores any span in the ThreadContext. */
  public static SpanHandle start( String name, boolean current, boolean root ) {
    Tracer t = tracer;
    if ( t == null ) {
      return NOOP;
    }
    try {
      Context parent = root ? Context.root() : parentFromThreadContext();
      if ( !root && parent == Context.root() ) {
        // No request around it -- schema loading, cache warm-up, a timer: not traced, rather
        // than each such statement starting a trace of its own.
        return NOOP;
      }
      Span span = t.spanBuilder( name )
        .setParent( parent )
        .setSpanKind( root ? SpanKind.SERVER : SpanKind.INTERNAL )
        .startSpan();
      return new SpanHandle( span, current );
    } catch ( Throwable e ) {
      return NOOP;
    }
  }

  private static Context parentFromThreadContext() {
    String traceId = ThreadContext.get( TRACE_ID );
    String spanId = ThreadContext.get( SPAN_ID );
    if ( traceId == null || spanId == null ) {
      return Context.root();
    }
    String flags = ThreadContext.get( TRACE_FLAGS );
    SpanContext sc = SpanContext.createFromRemoteParent(
      traceId, spanId,
      "01".equals( flags ) ? TraceFlags.getSampled() : TraceFlags.getDefault(),
      TraceState.getDefault() );
    return sc.isValid() ? Context.root().with( Span.wrap( sc ) ) : Context.root();
  }

  /** The current trace id from the ThreadContext, or null. */
  public static String currentTraceId() {
    return ThreadContext.get( TRACE_ID );
  }

  /** A started span. Every method is safe to call on {@link #NOOP} and never throws. */
  public static class SpanHandle implements AutoCloseable {
    private final Span span;
    private final boolean current;
    private String prevTrace;
    private String prevSpan;
    private String prevFlags;
    private boolean ended;

    SpanHandle( Span span, boolean current ) {
      this.span = span;
      this.current = current && span != null;
      if ( this.current ) {
        SpanContext sc = span.getSpanContext();
        prevTrace = ThreadContext.get( TRACE_ID );
        prevSpan = ThreadContext.get( SPAN_ID );
        prevFlags = ThreadContext.get( TRACE_FLAGS );
        ThreadContext.put( TRACE_ID, sc.getTraceId() );
        ThreadContext.put( SPAN_ID, sc.getSpanId() );
        ThreadContext.put( TRACE_FLAGS, sc.isSampled() ? "01" : "00" );
      }
    }

    public boolean isRecording() {
      return span != null && span.isRecording();
    }

    public String traceId() {
      return span == null ? null : span.getSpanContext().getTraceId();
    }

    public String spanId() {
      return span == null ? null : span.getSpanContext().getSpanId();
    }

    public SpanHandle set( String key, String value ) {
      if ( span != null && value != null ) {
        try {
          span.setAttribute( key, value );
        } catch ( Throwable ignored ) {
          // never fail the caller
        }
      }
      return this;
    }

    public SpanHandle set( String key, long value ) {
      if ( span != null ) {
        try {
          span.setAttribute( key, value );
        } catch ( Throwable ignored ) {
          // never fail the caller
        }
      }
      return this;
    }

    public SpanHandle set( String key, boolean value ) {
      if ( span != null ) {
        try {
          span.setAttribute( key, value );
        } catch ( Throwable ignored ) {
          // never fail the caller
        }
      }
      return this;
    }

    /** Statement text, redacted, only if text is on. */
    public SpanHandle text( String key, String value ) {
      if ( span != null && value != null && text ) {
        set( key, redact( value ) );
      }
      return this;
    }

    /** An event with one text attribute (redacted, only if text is on). */
    public SpanHandle event( String name, String key, String value ) {
      if ( span != null ) {
        try {
          if ( value != null && text ) {
            span.addEvent( name, Attributes.of( AttributeKey.stringKey( key ), redact( value ) ) );
          } else {
            span.addEvent( name );
          }
        } catch ( Throwable ignored ) {
          // never fail the caller
        }
      }
      return this;
    }

    /** Marks the span failed, with the exception's type and its redacted message. */
    public SpanHandle error( Throwable t ) {
      if ( span != null && t != null ) {
        try {
          span.setStatus( StatusCode.ERROR, redact( String.valueOf( t.getMessage() ) ) );
          span.setAttribute( "exception.type", t.getClass().getName() );
          String message = t.getMessage();
          if ( message != null ) {
            span.setAttribute( "exception.message", redact( message ) );
          }
        } catch ( Throwable ignored ) {
          // never fail the caller
        }
      }
      return this;
    }

    public SpanHandle error( String message ) {
      if ( span != null ) {
        try {
          span.setStatus( StatusCode.ERROR, redact( message ) );
        } catch ( Throwable ignored ) {
          // never fail the caller
        }
      }
      return this;
    }

    /** Ends the span and, if it was current, restores the previous span ids. Idempotent. */
    public void end() {
      if ( span == null || ended ) {
        return;
      }
      ended = true;
      try {
        span.end();
      } catch ( Throwable ignored ) {
        // never fail the caller
      }
      if ( current ) {
        restore( TRACE_ID, prevTrace );
        restore( SPAN_ID, prevSpan );
        restore( TRACE_FLAGS, prevFlags );
      }
    }

    @Override
    public void close() {
      end();
    }

    private static void restore( String key, String value ) {
      if ( value == null ) {
        ThreadContext.remove( key );
      } else {
        ThreadContext.put( key, value );
      }
    }
  }

  /** The span handle when tracing is off: does nothing. */
  public static final SpanHandle NOOP = new SpanHandle( null, false );

  // ------------------------------------------------------------------
  // Redaction: the same rules as the MCP traffic log (emondrian.mcp.McpAudit)

  private static final Pattern INLINE_PASSWORD = Pattern.compile(
    "(?i)((?:jdbc)?pass(?:word|wd)?|pwd|secret|token)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^&;,\\s'\"]+)" );
  private static final Pattern BEARER =
    Pattern.compile( "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}" );
  private static final Pattern LICENCE_FILE = Pattern.compile( "(?i)[\\w.()\\[\\] -]*\\.lic\\b" );
  private static final Pattern PEM =
    Pattern.compile( "-----BEGIN [A-Z ]+-----[\\s\\S]*?(-----END [A-Z ]+-----|$)" );

  public static String redact( String s ) {
    if ( s == null || s.isEmpty() ) {
      return s;
    }
    String r = PEM.matcher( s ).replaceAll( "[REDACTED PEM]" );
    r = INLINE_PASSWORD.matcher( r ).replaceAll( "$1$2[REDACTED]" );
    r = BEARER.matcher( r ).replaceAll( "$1 [REDACTED]" );
    return LICENCE_FILE.matcher( r ).replaceAll( "[licence file]" );
  }

  /** "DAX" or "MDX" by the statement's first keyword, as XmlaUtil.isDaxQuery decides. */
  public static String language( String statement ) {
    if ( statement == null ) {
      return "MDX";
    }
    String head = statement.stripLeading().toUpperCase( Locale.ROOT );
    return head.startsWith( "EVALUATE" ) || head.startsWith( "DEFINE" ) ? "DAX" : "MDX";
  }
}
