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

import org.hyperledger.besu.ethereum.referencetests.BlockExceptionMatcher;

import java.util.Set;

/**
 * Checks a fixture's expected exception against the message Besu rejected the block with, as hive's
 * consume does: the message must map to one of the expected exceptions, so a block rejected for a
 * different reason, or for one the mapping does not know, fails the fixture.
 *
 * <p>The mapping itself lives in {@code block-exception-mapping.json}, shared with the JUnit
 * reference tests — see {@link BlockExceptionMatcher}. This class only phrases the failure.
 */
final class ExpectedExceptionCheck {

  private ExpectedExceptionCheck() {}

  /**
   * Checks a block-test block's expected exception against the error block import rejected it with.
   *
   * @param expectedException the fixture's expected exception, {@code |}-separated alternatives
   * @param besuMessage the validation error block import returned, or {@code null} if none
   * @return {@code null} when the error matches one of the expected exceptions, otherwise a failure
   *     reason
   */
  static String importMismatch(final String expectedException, final String besuMessage) {
    if (besuMessage != null && BlockExceptionMatcher.matches(expectedException, besuMessage)) {
      return null;
    }
    return describe("exception", expectedException, besuMessage, false);
  }

  /**
   * Checks an engine-test payload's expected validation error against the message Besu returned
   * with the INVALID status.
   *
   * @param expectedValidationError the fixture's expected validationError, {@code |}-separated
   *     alternatives
   * @param besuMessage the message Besu returned with the INVALID status
   * @return {@code null} when the actual error matches one of the expected alternatives, otherwise
   *     a failure reason
   */
  static String engineMismatch(final String expectedValidationError, final String besuMessage) {
    if (besuMessage != null
        && BlockExceptionMatcher.matchesEngine(expectedValidationError, besuMessage)) {
      return null;
    }
    return describe("validation error", expectedValidationError, besuMessage, true);
  }

  private static String describe(
      final String label,
      final String expected,
      final String besuMessage,
      final boolean includeEngine) {
    final Set<String> actual = BlockExceptionMatcher.matchingExceptions(besuMessage, includeEngine);
    return String.format(
        "expected %s %s, but Besu returned %s (\"%s\")",
        label, expected, actual.isEmpty() ? "an unmapped error" : actual, besuMessage);
  }
}
