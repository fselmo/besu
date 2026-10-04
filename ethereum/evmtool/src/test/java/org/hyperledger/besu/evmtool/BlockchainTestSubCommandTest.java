/*
 * Copyright contributors to Hyperledger Besu.
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
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Regression coverage for {@code block-test --json-array} when a block's RLP cannot be decoded (see
 * #11328).
 */
class BlockchainTestSubCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** An Amsterdam fixture with one self-transfer block and its block access list. */
  private static final Path FIXTURE =
      Path.of(BlockchainTestSubCommandTest.class.getResource("bal-self-transfer.json").getPath());

  /** An Amsterdam fixture whose block is rejected for an access list missing an entry. */
  private static final Path INVALID_ACCESS_LIST =
      Path.of(
          BlockchainTestSubCommandTest.class
              .getResource("bal-invalid-block-access-list.json")
              .getPath());

  /** An Amsterdam fixture whose block is rejected for a gas limit below the minimum. */
  private static final Path GAS_LIMIT_BELOW_MINIMUM =
      Path.of(
          BlockchainTestSubCommandTest.class
              .getResource("header-gas-limit-below-minimum.json")
              .getPath());

  /**
   * A TangerineWhistle and a SpuriousDragon test, each with a rejected block and then an imported
   * one; EEST names these forks this way rather than EIP150 and EIP158.
   */
  private static final Path TANGERINE_WHISTLE_AND_SPURIOUS_DRAGON =
      Path.of(
          BlockchainTestSubCommandTest.class
              .getResource("tangerine-whistle-and-spurious-dragon.json")
              .getPath());

  private static final String INVALID_BLOCK_HASH =
      "0xe07099693533f8c5b69f1030a1932f538ae19e0631dd9951d6d0a210950d8bbb";

  @TempDir Path tempDir;

  @Test
  void importedBlocksLeaveRejectionsEmpty() throws IOException {
    final JsonNode result = result(FIXTURE, false);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("rejections").isArray()).isTrue();
    assertThat(result.get("rejections")).isEmpty();
  }

  @ParameterizedTest(name = "--bal-sequential={0}")
  @ValueSource(booleans = {false, true})
  void rejectedBlockIsReportedWithBesusError(final boolean sequential) throws IOException {
    final JsonNode result = result(INVALID_ACCESS_LIST, sequential);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("rejections")).hasSize(1);
    final JsonNode rejection = result.get("rejections").get(0);
    assertThat(rejection.get("index").asInt()).isZero();
    assertThat(rejection.get("hash").asText()).isEqualTo(INVALID_BLOCK_HASH);
    assertThat(rejection.get("error").asText()).contains("Block access list hash mismatch");
  }

  @Test
  void blockRejectedForAnotherReasonPassesAndReportsTheError() throws IOException {
    final Path otherReason =
        withFirstBlock(
            INVALID_ACCESS_LIST,
            block ->
                block.put("expectException", "TransactionException.INSUFFICIENT_ACCOUNT_FUNDS"));

    final JsonNode result = result(otherReason, false);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("error").isNull()).isTrue();
    assertThat(result.get("rejections").get(0).get("error").asText())
        .contains("Block access list hash mismatch");
  }

  @ParameterizedTest(name = "--bal-sequential={0}")
  @ValueSource(booleans = {false, true})
  void headerRejectionNamesTheRuleThatFailed(final boolean sequential) throws IOException {
    final JsonNode result = result(GAS_LIMIT_BELOW_MINIMUM, sequential);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("rejections")).hasSize(1);
    assertThat(result.get("rejections").get(0).get("error").asText())
        .isEqualTo(
            "Header validation failed (LIGHT) [Invalid block header: gasLimit = 0 is outside range"
                + " 5000 --> 9223372036854775807]");
  }

  @Test
  void tangerineWhistleAndSpuriousDragonFixturesRun() throws IOException {
    final JsonNode results =
        MAPPER.readTree(run("--json-array", TANGERINE_WHISTLE_AND_SPURIOUS_DRAGON.toString()));

    assertThat(results).hasSize(2);
    for (final JsonNode result : results) {
      assertThat(result.get("pass").asBoolean()).as(result.get("error").asText()).isTrue();
      assertThat(result.get("rejections")).hasSize(1);
    }
  }

  @Test
  void blockThatFailsToDecodeIsReportedWithTheDecoderMessage() throws IOException {
    final Path undecodable = withFirstBlock(INVALID_ACCESS_LIST, block -> block.put("rlp", "0xc0"));

    final JsonNode result = result(undecodable, false);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("rejections")).hasSize(1);
    final JsonNode rejection = result.get("rejections").get(0);
    assertThat(rejection.get("index").asInt()).isZero();
    assertThat(rejection.has("hash")).isFalse();
    assertThat(rejection.get("error").asText()).isNotEmpty();
  }

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
  void exceptionDuringImportFailsItsTestAndTheRunContinues() throws IOException {
    // No schedule has this name, so importing the test's block throws.
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(FIXTURE.toFile());
    final ObjectNode throwing = (ObjectNode) fixture.elements().next().deepCopy();
    throwing.put("network", "NoSuchFork");
    final ObjectNode bothTests = MAPPER.createObjectNode().set("throwing", throwing);
    bothTests.setAll(fixture);
    final Path file = tempDir.resolve("throwing-then-passing.json");
    Files.writeString(file, MAPPER.writeValueAsString(bothTests));

    final JsonNode results = MAPPER.readTree(run("--json-array", file.toString()));

    assertThat(results).hasSize(2);
    assertThat(results.get(0).get("name").asText()).isEqualTo("throwing");
    assertThat(results.get(0).get("pass").asBoolean()).isFalse();
    assertThat(results.get(0).get("error").asText())
        .startsWith("Unexpected exception importing block: java.lang.NullPointerException");
    assertThat(results.get(1).get("pass").asBoolean()).isTrue();
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
    return result(fixture, sequential).get("pass").asBoolean();
  }

  private static JsonNode result(final Path fixture, final boolean sequential) throws IOException {
    final List<String> args = new ArrayList<>(List.of("--json-array"));
    if (sequential) {
      args.add("--bal-sequential");
    }
    args.add(fixture.toString());
    return MAPPER.readTree(run(args.toArray(String[]::new))).get(0);
  }

  private Path withFirstBlock(final Path fixture, final Consumer<ObjectNode> change)
      throws IOException {
    final ObjectNode test = (ObjectNode) MAPPER.readTree(fixture.toFile());
    change.accept((ObjectNode) test.elements().next().get("blocks").get(0));
    final Path changed = tempDir.resolve("changed.json");
    Files.writeString(changed, MAPPER.writeValueAsString(test));
    return changed;
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

  @Test
  void jsonArrayReportsUndecodableRlpAsFailedRatherThanOmittingTheTest() throws Exception {
    final String output = runJsonArray("blockchain-truncated-rlp.json");
    final List<Map<String, Object>> results = MAPPER.readValue(output, new TypeReference<>() {});

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().get("name")).isEqualTo("truncated_rlp_no_expectException");
    assertThat(results.getFirst().get("pass")).isEqualTo(false);
    assertThat(results.getFirst().get("error").toString()).contains("RLP exception");
  }

  @Test
  void jsonArrayKeepsSiblingTestsWhenOneBlockRlpIsCorrupt() throws Exception {
    final String output = runJsonArray("blockchain-mixed-rlp.json");
    final List<Map<String, Object>> results =
        MAPPER.readValue(output, new TypeReference<List<Map<String, Object>>>() {});

    assertThat(results).hasSize(2);
    final Map<String, Map<String, Object>> byName =
        results.stream()
            .collect(java.util.stream.Collectors.toMap(r -> (String) r.get("name"), r -> r));
    assertThat(byName).containsKeys("good_london_block", "truncated_rlp_no_expectException");
    assertThat(byName.get("truncated_rlp_no_expectException").get("pass")).isEqualTo(false);
    assertThat(byName.get("truncated_rlp_no_expectException").get("error").toString())
        .contains("RLP exception");
  }

  @Test
  void jsonArrayKeepsFirstBlockFailureWhenLaterBlockImports() throws Exception {
    final String output = runJsonArray("blockchain-truncated-then-good-rlp.json");
    final List<Map<String, Object>> results = MAPPER.readValue(output, new TypeReference<>() {});

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().get("pass")).isEqualTo(false);
    assertThat(results.getFirst().get("error")).asString().contains("RLP exception");
  }

  private static String runJsonArray(final String fixtureResource) {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final EvmToolCommand parentCommand =
        new EvmToolCommand(System.in, new PrintWriter(baos, true, UTF_8));
    final BlockchainTestSubCommand command = new BlockchainTestSubCommand(parentCommand);
    new CommandLine(command)
        .parseArgs(
            "--json-array",
            "--workers",
            "1",
            BlockchainTestSubCommandTest.class.getResource(fixtureResource).getPath());
    command.run();
    assertThat(command.getExitCode()).isEqualTo(1);
    return baos.toString(UTF_8).trim();
  }
}
