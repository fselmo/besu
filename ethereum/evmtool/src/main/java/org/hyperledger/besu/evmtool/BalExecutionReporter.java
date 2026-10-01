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

import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.BlockExecutionPathListener;

import java.io.PrintStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;

/**
 * Under --bal-report, prints one JSON line to stderr for each block block-test and engine-test
 * execute, naming the executor that ran it, and one more when the parallel executor failed a block
 * and it was run again sequentially, so a fixture's verdict can be tied to the path that produced
 * it. Lines from different workers interleave; the block hash ties each to its block.
 */
final class BalExecutionReporter implements BlockExecutionPathListener {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final PrintStream err;

  @VisibleForTesting
  BalExecutionReporter(final PrintStream err) {
    this.err = err;
  }

  /** Starts reporting every block executed in this process, until {@link #uninstall()}. */
  static void install() {
    AbstractBlockProcessor.setExecutionPathListener(new BalExecutionReporter(System.err));
  }

  /** Stops reporting. */
  static void uninstall() {
    AbstractBlockProcessor.setExecutionPathListener(BlockExecutionPathListener.NONE);
  }

  @Override
  public void onParallel(final BlockHeader header, final String scheduler) {
    final ObjectNode line = event("balExecution", header);
    line.put("path", "parallel");
    line.put("reason", "");
    line.put("scheduler", scheduler);
    print(line);
  }

  @Override
  public void onSequential(final BlockHeader header, final String reason) {
    final ObjectNode line = event("balExecution", header);
    line.put("path", "sequential");
    line.put("reason", reason);
    print(line);
  }

  @Override
  public void onSequentialFallback(
      final BlockHeader header,
      final BlockProcessingResult parallelResult,
      final BlockProcessingResult sequentialResult) {
    final ObjectNode line = event("balFallback", header);
    line.put("parallelError", parallelResult.errorMessage.orElse(""));
    line.put("sequentialResult", sequentialResult.isSuccessful() ? "valid" : "invalid");
    line.put(
        "sequentialError",
        sequentialResult.isSuccessful() ? "" : sequentialResult.errorMessage.orElse(""));
    print(line);
  }

  private static ObjectNode event(final String name, final BlockHeader header) {
    final ObjectNode line = MAPPER.createObjectNode();
    line.put("event", name);
    line.put("block", header.getNumber());
    line.put("hash", header.getHash().toHexString());
    return line;
  }

  private void print(final ObjectNode line) {
    // One println per line, so lines from concurrent workers never split each other.
    err.println(line.toString());
  }
}
