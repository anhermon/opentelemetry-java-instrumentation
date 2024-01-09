/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.log4j.appender.v2_17;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.instrumentation.testing.junit.AgentInstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.semconv.SemanticAttributes;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

class Slf4jToLog4jTest {

  @RegisterExtension
  static final InstrumentationExtension testing = AgentInstrumentationExtension.create();

  private static final Logger logger = LoggerFactory.getLogger("abc");

  private static Stream<Arguments> provideParameters() {
    return Stream.of(
        Arguments.of(false, false),
        Arguments.of(false, true),
        Arguments.of(true, false),
        Arguments.of(true, true));
  }

  @ParameterizedTest
  @MethodSource("provideParameters")
  public void test(boolean logException, boolean withParent) throws InterruptedException {
    test(Logger::debug, Logger::debug, logException, withParent, null, null, null);
    testing.clearData();
    test(Logger::info, Logger::info, logException, withParent, "abc", Severity.INFO, "INFO");
    testing.clearData();
    test(Logger::warn, Logger::warn, logException, withParent, "abc", Severity.WARN, "WARN");
    testing.clearData();
    test(Logger::error, Logger::error, logException, withParent, "abc", Severity.ERROR, "ERROR");
    testing.clearData();
  }

  private static void test(
      OneArgLoggerMethod oneArgLoggerMethod,
      TwoArgLoggerMethod twoArgLoggerMethod,
      boolean logException,
      boolean withParent,
      String expectedLoggerName,
      Severity expectedSeverity,
      String expectedSeverityText)
      throws InterruptedException {

    // when
    if (withParent) {
      testing.runWithSpan(
          "parent", () -> performLogging(oneArgLoggerMethod, twoArgLoggerMethod, logException));
    } else {
      performLogging(oneArgLoggerMethod, twoArgLoggerMethod, logException);
    }

    // then
    if (withParent) {
      testing.waitForTraces(1);
    }

    if (expectedSeverity != null) {
      LogRecordData log = testing.waitForLogRecords(1).get(0);
      assertThat(log)
          .hasBody("xyz: 123")
          .hasInstrumentationScope(InstrumentationScopeInfo.builder(expectedLoggerName).build())
          .hasSeverity(expectedSeverity)
          .hasSeverityText(expectedSeverityText);
      if (logException) {
        assertThat(log)
            .hasAttributesSatisfyingExactly(
                equalTo(SemanticAttributes.THREAD_NAME, Thread.currentThread().getName()),
                equalTo(SemanticAttributes.THREAD_ID, Thread.currentThread().getId()),
                equalTo(SemanticAttributes.EXCEPTION_TYPE, IllegalStateException.class.getName()),
                equalTo(SemanticAttributes.EXCEPTION_MESSAGE, "hello"),
                satisfies(
                    SemanticAttributes.EXCEPTION_STACKTRACE,
                    v -> v.contains(Slf4jToLog4jTest.class.getName())));
      } else {
        assertThat(log)
            .hasAttributesSatisfyingExactly(
                equalTo(SemanticAttributes.THREAD_NAME, Thread.currentThread().getName()),
                equalTo(SemanticAttributes.THREAD_ID, Thread.currentThread().getId()));
      }

      if (withParent) {
        assertThat(log).hasSpanContext(testing.spans().get(0).getSpanContext());
      } else {
        assertThat(log.getSpanContext().isValid()).isFalse();
      }

    } else {
      Thread.sleep(500); // sleep a bit just to make sure no log is captured
      assertThat(testing.logRecords()).isEmpty();
    }
  }

  @Test
  void testMdc() {
    MDC.put("key1", "val1");
    MDC.put("key2", "val2");
    try {
      logger.info("xyz: {}", 123);
    } finally {
      MDC.clear();
    }

    LogRecordData log = testing.waitForLogRecords(1).get(0);
    assertThat(log)
        .hasBody("xyz: 123")
        .hasInstrumentationScope(InstrumentationScopeInfo.builder("abc").build())
        .hasSeverity(Severity.INFO)
        .hasSeverityText("INFO")
        .hasAttributesSatisfyingExactly(
            equalTo(AttributeKey.stringKey("key1"), "val1"),
            equalTo(AttributeKey.stringKey("key2"), "val2"),
            equalTo(SemanticAttributes.THREAD_NAME, Thread.currentThread().getName()),
            equalTo(SemanticAttributes.THREAD_ID, Thread.currentThread().getId()));
  }

  @Test
  public void testMarker() {

    String markerName = "aMarker";
    Marker marker = MarkerFactory.getMarker(markerName);

    logger.info(marker, "Message");

    LogRecordData log = testing.waitForLogRecords(1).get(0);
    assertThat(log)
        .hasAttributesSatisfyingExactly(
            equalTo(SemanticAttributes.THREAD_NAME, Thread.currentThread().getName()),
            equalTo(SemanticAttributes.THREAD_ID, Thread.currentThread().getId()),
            equalTo(AttributeKey.stringKey("log4j.marker"), markerName));
  }

  private static void performLogging(
      OneArgLoggerMethod oneArgLoggerMethod,
      TwoArgLoggerMethod twoArgLoggerMethod,
      boolean logException) {
    if (logException) {
      twoArgLoggerMethod.call(logger, "xyz: {}", 123, new IllegalStateException("hello"));
    } else {
      oneArgLoggerMethod.call(logger, "xyz: {}", 123);
    }
  }

  @FunctionalInterface
  interface OneArgLoggerMethod {
    void call(Logger logger, String msg, Object arg);
  }

  @FunctionalInterface
  interface TwoArgLoggerMethod {
    void call(Logger logger, String msg, Object arg1, Object arg2);
  }
}
