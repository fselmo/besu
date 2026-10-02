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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class EngineTestSubCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String INSUFFICIENT_FUNDS =
      "TransactionException.INSUFFICIENT_ACCOUNT_FUNDS";

  /** An Amsterdam engine fixture whose payload is INVALID for an access list missing an entry. */
  private static final Path INVALID_ACCESS_LIST =
      Path.of(
          EngineTestSubCommandTest.class
              .getResource("bal-invalid-block-access-list-engine.json")
              .getPath());

  @TempDir Path tempDir;

  @ParameterizedTest(name = "--bal-sequential={0}")
  @ValueSource(booleans = {false, true})
  void payloadRejectedForTheExpectedReasonPasses(final boolean sequential) throws IOException {
    assertThat(result(INVALID_ACCESS_LIST, sequential).get("pass").asBoolean()).isTrue();
  }

  @ParameterizedTest(name = "--bal-sequential={0}")
  @ValueSource(booleans = {false, true})
  void payloadRejectedForAnotherReasonFails(final boolean sequential) throws IOException {
    final JsonNode result = result(withValidationError(INSUFFICIENT_FUNDS), sequential);

    assertThat(result.get("pass").asBoolean()).isFalse();
    assertThat(result.get("error").asText())
        .contains(INSUFFICIENT_FUNDS)
        .contains("BlockException.INVALID_BLOCK_ACCESS_LIST")
        .contains("Block access list hash mismatch");
  }

  private Path withValidationError(final String exception) throws IOException {
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(INVALID_ACCESS_LIST.toFile());
    final ObjectNode payload =
        (ObjectNode) fixture.elements().next().get("engineNewPayloads").get(0);
    payload.put("validationError", exception);
    final Path changed = tempDir.resolve("wrong-reason.json");
    Files.writeString(changed, MAPPER.writeValueAsString(fixture));
    return changed;
  }

  private static JsonNode result(final Path fixture, final boolean sequential) throws IOException {
    final List<String> args = new ArrayList<>(List.of("--json-array"));
    if (sequential) {
      args.add("--bal-sequential");
    }
    args.add(fixture.toString());
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final EngineTestSubCommand engineTest =
        new EngineTestSubCommand(
            new EvmToolCommand(System.in, new PrintWriter(stdout, true, UTF_8)));
    new CommandLine(engineTest).parseArgs(args.toArray(String[]::new));
    engineTest.run();
    return MAPPER.readTree(stdout.toString(UTF_8)).get(0);
  }
}
