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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

  @TempDir Path tempDir;

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

    final String stdout = run("--json-array", file.toString());
    final JsonNode results = MAPPER.readTree(stdout.lines().reduce((first, last) -> last).get());

    assertThat(results).hasSize(2);
    assertThat(results.get(0).get("name").asText()).isEqualTo("throwing");
    assertThat(results.get(0).get("pass").asBoolean()).isFalse();
    assertThat(results.get(0).get("error").asText())
        .startsWith("Unexpected exception importing block: java.lang.NullPointerException");
    assertThat(results.get(1).get("pass").asBoolean()).isTrue();
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
