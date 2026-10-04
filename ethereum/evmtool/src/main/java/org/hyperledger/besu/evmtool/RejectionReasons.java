/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.evmtool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.google.common.annotations.VisibleForTesting;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.slf4j.Log4jLoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Adds the specific reason Besu logs for a rejected block to the runners' rejection errors.
 *
 * <p>For a header, body or block access list that fails validation, the block validator returns a
 * generic error ({@code Header validation failed (LIGHT)}, {@code failed to validate output of
 * imported block}, {@code Block access list validation failed for block ...}), while the rule that
 * failed logs which check it was. evmtool turns logging off, so that line is lost. While installed,
 * this captures those validators' log lines on the importing thread, and {@link #append} adds them
 * to the error in brackets. The validators and their messages are unchanged.
 */
final class RejectionReasons {

  /** The loggers of the validators whose failures the block validator reports generically. */
  @VisibleForTesting
  static final List<String> VALIDATOR_LOGGERS =
      List.of(
          "org.hyperledger.besu.ethereum.mainnet.headervalidationrules",
          "org.hyperledger.besu.consensus.merge.headervalidationrules",
          "org.hyperledger.besu.ethereum.mainnet.MainnetBlockBodyValidator",
          "org.hyperledger.besu.ethereum.mainnet.BaseFeeBlockBodyValidator",
          "org.hyperledger.besu.ethereum.mainnet.MainnetBlockAccessListValidator",
          // Nested classes log under their binary names, which are not children of the outer one.
          "org.hyperledger.besu.ethereum.mainnet.WithdrawalsValidator$ProhibitedWithdrawals",
          "org.hyperledger.besu.ethereum.mainnet.WithdrawalsValidator$AllowedWithdrawals",
          "org.hyperledger.besu.ethereum.mainnet.WithdrawalsValidator$NotApplicableWithdrawals");

  // Lines that dump the block or the access list rather than name a reason.
  private static final List<String> DUMPS =
      List.of("Invalid block RLP", "Transaction receipt found in the invalid block", "--- BAL");

  // Set only while a runner imports a block, so each worker collects its own block's lines.
  private static final ThreadLocal<List<String>> CAPTURED = new ThreadLocal<>();

  private static final String APPENDER_NAME = "evmtool-rejection-reasons";

  private static int installs = 0;

  // The logger configurations install() added, removed again on uninstall.
  private static final List<String> ADDED_LOGGERS = new ArrayList<>();

  private RejectionReasons() {}

  /** Starts capturing the validators' log lines, until {@link #uninstall()}. */
  static synchronized void install() {
    if (installs++ > 0) {
      return;
    }
    // Without Log4j as the logging backend (the native image binds slf4j-nop) nothing is logged, so
    // errors keep their generic text.
    final LoggerContext context = loggerContext();
    if (context == null) {
      return;
    }
    final Configuration config = context.getConfiguration();
    final CapturingAppender appender = new CapturingAppender();
    appender.start();
    config.addAppender(appender);
    for (final String name : VALIDATOR_LOGGERS) {
      // A logger the user configured keeps its level, so its reasons are not captured.
      if (config.getLoggers().containsKey(name)) {
        continue;
      }
      final LoggerConfig loggerConfig = new LoggerConfig(name, Level.DEBUG, false);
      loggerConfig.addAppender(appender, Level.DEBUG, null);
      config.addLogger(name, loggerConfig);
      ADDED_LOGGERS.add(name);
    }
    context.updateLoggers();
  }

  /** Stops capturing, removing what {@link #install()} added. */
  static synchronized void uninstall() {
    if (installs == 0 || --installs > 0) {
      return;
    }
    final LoggerContext context = loggerContext();
    if (context == null) {
      return;
    }
    final Configuration config = context.getConfiguration();
    ADDED_LOGGERS.forEach(config::removeLogger);
    ADDED_LOGGERS.clear();
    final Appender appender = config.getAppenders().remove(APPENDER_NAME);
    if (appender != null) {
      appender.stop();
    }
    context.updateLoggers();
  }

  // The context besu's LogConfigurator configures, found the same way.
  private static LoggerContext loggerContext() {
    LoggerFactory.getLogger(RejectionReasons.class);
    if (LoggerFactory.getILoggerFactory() instanceof Log4jLoggerFactory factory
        && factory.getLoggerContexts().stream().findFirst().orElse(null)
            instanceof LoggerContext context) {
      return context;
    }
    return null;
  }

  /**
   * Runs a block import, collecting the validators' log lines it produces into {@code reasons}.
   *
   * @param reasons where the lines go
   * @param importBlock the import
   * @return the import's result
   */
  static <T> T capturing(final List<String> reasons, final Supplier<T> importBlock) {
    CAPTURED.set(reasons);
    try {
      return importBlock.get();
    } finally {
      CAPTURED.remove();
    }
  }

  /**
   * Returns the client's error followed by the captured reasons it does not already contain, in
   * brackets and separated by {@code ; }, or the error alone when there are none.
   *
   * @param error the client's error
   * @param reasons the captured lines
   * @return the error with its reasons
   */
  static String append(final String error, final List<String> reasons) {
    final List<String> added = new ArrayList<>();
    for (final String reason : reasons) {
      if (!error.contains(reason) && !added.contains(reason)) {
        added.add(reason);
      }
    }
    return added.isEmpty() ? error : error + " [" + String.join("; ", added) + "]";
  }

  @VisibleForTesting
  static void capture(final String message) {
    final List<String> reasons = CAPTURED.get();
    if (reasons == null || message == null) {
      return;
    }
    final String firstLine = message.lines().findFirst().orElse("").strip();
    if (firstLine.isEmpty() || DUMPS.stream().anyMatch(firstLine::startsWith)) {
      return;
    }
    reasons.add(firstLine);
  }

  private static final class CapturingAppender extends AbstractAppender {
    CapturingAppender() {
      super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(final LogEvent event) {
      capture(event.getMessage().getFormattedMessage());
    }
  }
}
