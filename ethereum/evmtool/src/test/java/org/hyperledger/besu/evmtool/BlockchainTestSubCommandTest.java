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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class BlockchainTestSubCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** An Amsterdam fixture with one self-transfer block and its block access list. */
  private static final Path FIXTURE =
      Path.of(BlockchainTestSubCommandTest.class.getResource("bal-self-transfer.json").getPath());

  @TempDir Path tempDir;

  @ParameterizedTest(name = "--bal-sequential={0}")
  @ValueSource(booleans = {false, true})
  void accessListNotMatchingTheHeaderIsDroppedNotRejected(final boolean sequential)
      throws IOException {
    assertThat(passes(FIXTURE, sequential)).isTrue();
    assertThat(passes(withLastAccessListAccountDropped(), sequential)).isTrue();
  }

  @Test
  void droppedAccessListRunsOnTheOptimisticScheduler() throws IOException {
    final JsonNode delivered = decisionLine(FIXTURE, false);
    assertThat(delivered.get("reason").asText()).isEmpty();
    assertThat(delivered.get("scheduler").asText()).isEqualTo("bal");

    final JsonNode dropped = decisionLine(withLastAccessListAccountDropped(), false);
    assertThat(dropped.get("path").asText()).isEqualTo("parallel");
    assertThat(dropped.get("reason").asText()).isEqualTo("bad-access-list");
    assertThat(dropped.get("scheduler").asText()).isEqualTo("optimistic");
  }

  @Test
  void droppedAccessListReasonTakesPrecedenceOverTheSwitch() throws IOException {
    assertThat(decisionLine(FIXTURE, true).get("reason").asText()).isEqualTo("disabled");

    final JsonNode dropped = decisionLine(withLastAccessListAccountDropped(), true);
    assertThat(dropped.get("path").asText()).isEqualTo("sequential");
    assertThat(dropped.get("reason").asText()).isEqualTo("bad-access-list");
  }

  @Test
  void jsonArrayStdoutIsTheArrayAlone() throws IOException {
    final JsonNode stdout = MAPPER.readTree(run("--json-array", FIXTURE.toString()));

    assertThat(stdout.isArray()).isTrue();
    assertThat(stdout).hasSize(1);
  }

  @Test
  void balReportPrintsTheDecisionLineOnStderr() throws IOException {
    final List<String> lines =
        eventLines(stderr("--json-array", "--bal-report", FIXTURE.toString()));

    assertThat(lines).hasSize(1);
    final JsonNode line = MAPPER.readTree(lines.get(0));
    assertThat(line.get("event").asText()).isEqualTo("balExecution");
    assertThat(line.get("path").asText()).isEqualTo("parallel");
  }

  @Test
  void withoutBalReportNoEventLineIsPrinted() {
    assertThat(eventLines(stderr("--json-array", FIXTURE.toString()))).isEmpty();
  }

  @Test
  void missingPathFailsBeforeAnyTestRuns() {
    assertFailsBeforeAnyTestRuns(tempDir.resolve("does-not-exist.json"));
  }

  @Test
  void unreadablePathFailsBeforeAnyTestRuns() throws IOException {
    final Path unreadable = Files.copy(FIXTURE, tempDir.resolve("unreadable.json"));
    assumeTrue(unreadable.toFile().setReadable(false) && !Files.isReadable(unreadable));
    assertFailsBeforeAnyTestRuns(unreadable);
  }

  private static void assertFailsBeforeAnyTestRuns(final Path badPath) {
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final PrintStream originalErr = System.err;
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    final BlockchainTestSubCommand blockTest =
        new BlockchainTestSubCommand(
            new EvmToolCommand(System.in, new PrintWriter(stdout, true, UTF_8)));
    new CommandLine(blockTest).parseArgs("--json-array", FIXTURE.toString(), badPath.toString());
    System.setErr(new PrintStream(stderr, true, UTF_8));
    try {
      blockTest.run();
    } finally {
      System.setErr(originalErr);
    }

    assertThat(blockTest.getExitCode()).isEqualTo(1);
    assertThat(stdout.toString(UTF_8).trim()).isEqualTo("[]");
    assertThat(stderr.toString(UTF_8)).contains(badPath.toString());
  }

  private static JsonNode decisionLine(final Path fixture, final boolean sequential)
      throws IOException {
    final List<String> args = new ArrayList<>(List.of("--json-array", "--bal-report"));
    if (sequential) {
      args.add("--bal-sequential");
    }
    args.add(fixture.toString());
    final List<String> lines = eventLines(stderr(args.toArray(String[]::new)));
    assertThat(lines).hasSize(1);
    return MAPPER.readTree(lines.get(0));
  }

  private boolean passes(final Path fixture, final boolean sequential) throws IOException {
    final List<String> args = new ArrayList<>(List.of("--json-array"));
    if (sequential) {
      args.add("--bal-sequential");
    }
    args.add(fixture.toString());
    return MAPPER.readTree(run(args.toArray(String[]::new))).get(0).get("pass").asBoolean();
  }

  private Path withLastAccessListAccountDropped() throws IOException {
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(FIXTURE.toFile());
    final ArrayNode accessList =
        (ArrayNode) fixture.elements().next().get("blocks").get(0).get("blockAccessList");
    accessList.remove(accessList.size() - 1);
    final Path corrupted = tempDir.resolve("corrupted.json");
    Files.writeString(corrupted, MAPPER.writeValueAsString(fixture));
    return corrupted;
  }

  private static List<String> eventLines(final String stderr) {
    return stderr.lines().filter(line -> line.startsWith("{\"event\":")).toList();
  }

  private static String stderr(final String... args) {
    final PrintStream originalErr = System.err;
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    System.setErr(new PrintStream(stderr, true, UTF_8));
    try {
      run(args);
    } finally {
      System.setErr(originalErr);
    }
    return stderr.toString(UTF_8);
  }

  private static String run(final String... args) {
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final BlockchainTestSubCommand blockTest =
        new BlockchainTestSubCommand(
            new EvmToolCommand(System.in, new PrintWriter(stdout, true, UTF_8)));
    new CommandLine(blockTest).parseArgs(args);
    blockTest.run();
    return stdout.toString(UTF_8);
  }
}
