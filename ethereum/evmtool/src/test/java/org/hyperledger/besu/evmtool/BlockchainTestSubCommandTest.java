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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
  void deliveredAccessListIsValidatedAgainstExecution(final boolean sequential) throws IOException {
    assertThat(passes(FIXTURE, sequential)).isTrue();
    assertThat(passes(withLastAccessListAccountDropped(), sequential)).isFalse();
  }

  @Test
  void jsonArrayStdoutIsTheArrayAlone() throws IOException {
    final JsonNode stdout = MAPPER.readTree(run("--json-array", FIXTURE.toString()));

    assertThat(stdout.isArray()).isTrue();
    assertThat(stdout).hasSize(1);
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
