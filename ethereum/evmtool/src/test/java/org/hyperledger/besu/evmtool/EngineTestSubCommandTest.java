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
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class EngineTestSubCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** An Amsterdam engine fixture whose payload is INVALID for an access list missing an entry. */
  private static final Path INVALID_ACCESS_LIST =
      Path.of(
          EngineTestSubCommandTest.class
              .getResource("bal-invalid-block-access-list-engine.json")
              .getPath());

  /** An Amsterdam engine fixture whose payload is INVALID for a gas limit below the minimum. */
  private static final Path GAS_LIMIT_BELOW_MINIMUM =
      Path.of(
          EngineTestSubCommandTest.class
              .getResource("header-gas-limit-below-minimum-engine.json")
              .getPath());

  private static final String INVALID_BLOCK_HASH =
      "0xe07099693533f8c5b69f1030a1932f538ae19e0631dd9951d6d0a210950d8bbb";

  @TempDir Path tempDir;

  @ParameterizedTest(name = "--bal-sequential={0}")
  @ValueSource(booleans = {false, true})
  void invalidPayloadIsReportedWithBesusError(final boolean sequential) throws IOException {
    final JsonNode result = result(INVALID_ACCESS_LIST, sequential);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("rejections")).hasSize(1);
    final JsonNode rejection = result.get("rejections").get(0);
    assertThat(rejection.get("index").asInt()).isZero();
    assertThat(rejection.get("hash").asText()).isEqualTo(INVALID_BLOCK_HASH);
    assertThat(rejection.get("error").asText()).contains("Block access list hash mismatch");
  }

  @Test
  void headerRejectionNamesTheRuleThatFailed() throws IOException {
    final JsonNode result = result(GAS_LIMIT_BELOW_MINIMUM, false);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("rejections")).hasSize(1);
    assertThat(result.get("rejections").get(0).get("error").asText())
        .isEqualTo(
            "Header validation failed (FULL) [Invalid block header: gasLimit = 0 is outside range"
                + " 5000 --> 9223372036854775807]");
  }

  @Test
  void payloadRejectedForAnotherReasonStillFailsEngineTestsOwnCheck() throws IOException {
    final Path otherReason =
        withFirstPayload(
            payload ->
                payload.put("validationError", "TransactionException.INSUFFICIENT_ACCOUNT_FUNDS"));

    final JsonNode result = result(otherReason, false);

    assertThat(result.get("pass").asBoolean()).isFalse();
    assertThat(result.get("rejections").get(0).get("error").asText())
        .contains("Block access list hash mismatch");
  }

  @Test
  void payloadRejectedWithAJsonRpcErrorIsReportedWithItsCodeMessageAndData() throws IOException {
    final Path wrongVersion =
        withFirstPayload(
            payload -> {
              payload.put("newPayloadVersion", "4");
              payload.remove("validationError");
              payload.put("errorCode", "-32602");
            });

    final JsonNode result = result(wrongVersion, false);

    assertThat(result.get("pass").asBoolean()).isTrue();
    assertThat(result.get("rejections")).hasSize(1);
    final JsonNode rejection = result.get("rejections").get(0);
    assertThat(rejection.get("index").asInt()).isZero();
    assertThat(rejection.has("hash")).isFalse();
    // Code, message, then the error's data, which names the parameter Besu could not decode.
    assertThat(rejection.get("error").asText())
        .startsWith("-32602: Invalid engine payload parameter: ")
        .contains("Failed to decode block parameter");
  }

  @Test
  void testEndedBeforeAnyPayloadHasNoRejections() throws IOException {
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(INVALID_ACCESS_LIST.toFile());
    ((ObjectNode) fixture.elements().next()).put("network", "NoSuchFork");
    final Path unsupported = tempDir.resolve("unsupported.json");
    Files.writeString(unsupported, MAPPER.writeValueAsString(fixture));

    final JsonNode result = result(unsupported, false);

    assertThat(result.get("pass").asBoolean()).isFalse();
    assertThat(result.get("rejections").isArray()).isTrue();
    assertThat(result.get("rejections")).isEmpty();
  }

  private Path withFirstPayload(final Consumer<ObjectNode> change) throws IOException {
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(INVALID_ACCESS_LIST.toFile());
    change.accept((ObjectNode) fixture.elements().next().get("engineNewPayloads").get(0));
    final Path changed = tempDir.resolve("changed.json");
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
