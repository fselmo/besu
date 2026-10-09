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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ForkchoiceUpdatedResultV1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

class EngineTestSubCommandTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** An Amsterdam engine fixture with one self-transfer payload and its block access list. */
  private static final Path FIXTURE =
      Path.of(
          EngineTestSubCommandTest.class.getResource("bal-self-transfer-engine.json").getPath());

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

  @Test
  void balReportPrintsTheDecisionLineOnStderr() throws IOException {
    final PrintStream originalErr = System.err;
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    System.setErr(new PrintStream(stderr, true, UTF_8));
    try {
      run("--json-array", "--bal-report", FIXTURE.toString());
    } finally {
      System.setErr(originalErr);
    }

    final List<String> lines =
        stderr.toString(UTF_8).lines().filter(line -> line.startsWith("{\"event\":")).toList();
    assertThat(lines).hasSize(1);
    final JsonNode line = MAPPER.readTree(lines.get(0));
    assertThat(line.get("event").asText()).isEqualTo("balExecution");
    assertThat(line.get("path").asText()).isEqualTo("parallel");
  }

  @Test
  void unrecoverableSignatureMatchesInvalidSignatureVrs() {
    assertThat(
            EngineTestExceptionMapper.mismatch(
                "TransactionException.INVALID_SIGNATURE_VRS",
                "Block processing error: transaction invalid Internal Error in Besu -"
                    + " java.lang.IllegalStateException: Cannot recover public key from"
                    + " signature for MessageCall{type=FRONTIER, nonce=0}"))
        .isNull();
  }

  @Test
  void validForkchoiceUpdateIsNotAFailure() {
    assertThat(forkchoiceFailure(forkchoiceStatus(EngineStatus.VALID))).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = EngineStatus.class,
      names = {"SYNCING", "INVALID"})
  void forkchoiceUpdateThatIsNotValidFailsTheTest(final EngineStatus status) {
    assertThat(forkchoiceFailure(forkchoiceStatus(status)))
        .isEqualTo("status: expected VALID, got " + status + " (err: null)");
  }

  @Test
  void forkchoiceUpdateAnsweredWithAnErrorFailsTheTest() {
    assertThat(
            forkchoiceFailure(
                new JsonRpcErrorResponse(null, RpcErrorType.INVALID_FORKCHOICE_STATE)))
        .startsWith("error: -38002");
  }

  @Test
  void forkchoiceUpdateErrorCarriesTheExceptionBesuLogged() {
    final ExecutionEngineJsonRpcMethod forkchoiceUpdated = mock(ExecutionEngineJsonRpcMethod.class);
    // As Besu's world state provider does: catch the exception, log it, and fail the update.
    when(forkchoiceUpdated.syncResponse(any()))
        .thenAnswer(
            invocation -> {
              try {
                rollForward();
              } catch (final IllegalStateException e) {
                LoggerFactory.getLogger(
                        "org.hyperledger.besu.ethereum.trie.pathbased.bonsai.provider"
                            + ".PathBasedWorldStateProvider")
                    .warn("State rolling failed", e);
              }
              return new JsonRpcErrorResponse(
                  null, RpcErrorType.INTERNAL_ERROR, "Failed to set new head");
            });

    RejectionReasons.install();
    try {
      assertThat(EngineTestSubCommand.forkchoiceFailure(forkchoiceUpdated, 4, Hash.ZERO))
          .startsWith(
              "error: -32603 Internal error: Failed to set new head;"
                  + " java.lang.IllegalStateException: rolled past the trie log at"
                  + " org.hyperledger.besu.evmtool.EngineTestSubCommandTest.rollForward(");
    } finally {
      RejectionReasons.uninstall();
    }
  }

  private static void rollForward() {
    throw new IllegalStateException("rolled past the trie log");
  }

  private static JsonRpcResponse forkchoiceStatus(final EngineStatus status) {
    return new JsonRpcSuccessResponse(null, new ForkchoiceUpdatedResultV1(status, null));
  }

  private static String forkchoiceFailure(final JsonRpcResponse response) {
    final ExecutionEngineJsonRpcMethod forkchoiceUpdated = mock(ExecutionEngineJsonRpcMethod.class);
    when(forkchoiceUpdated.syncResponse(any())).thenReturn(response);
    return EngineTestSubCommand.forkchoiceFailure(forkchoiceUpdated, 4, Hash.ZERO);
  }

  private Path withFirstPayload(final Consumer<ObjectNode> change) throws IOException {
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(INVALID_ACCESS_LIST.toFile());
    change.accept((ObjectNode) fixture.elements().next().get("engineNewPayloads").get(0));
    final Path changed = tempDir.resolve("changed.json");
    Files.writeString(changed, MAPPER.writeValueAsString(fixture));
    return changed;
  }

  /**
   * A big block's storage map tripped Jackson's guard against hash-collision attacks on some runs,
   * depending on its hash seed. 40,000 zero-padded keys trip it on about a third of runs, so twenty
   * runs all read the file only with the guard off. The fixture's payload is one Besu rejects,
   * because the first engine-test run in a JVM stops the harness's scheduler and a valid payload
   * cannot run after that.
   */
  @Test
  void storageMapLargeEnoughToTripJacksonsCollisionGuardIsRead() throws IOException {
    final Path bigStorage = withBigPostStateStorage(INVALID_ACCESS_LIST);
    for (int run = 0; run < 20; run++) {
      final JsonNode results = MAPPER.readTree(run("--json-array", bigStorage.toString()));
      assertThat(results).hasSize(1);
      assertThat(results.get(0).get("pass").asBoolean()).isTrue();
    }
  }

  @Test
  void unreadableFileIsReportedWithItsErrorWhenNoTestRan() throws IOException {
    final Path notAFixture =
        Files.writeString(tempDir.resolve("not-a-fixture.json"), "{\"test\": [");
    final String reason = notAFixture + ": not readable as an engine test fixture";

    assertThat(run(notAFixture.toString()))
        .contains("No engine test was executed.")
        .contains(reason);
    assertThat(stderr("--json-array", notAFixture.toString()))
        .contains("Unreadable file " + reason);
  }

  /**
   * Copies {@code original} with 40,000 storage keys in one postState account, which the runner
   * parses but does not check, so the test still passes.
   */
  private Path withBigPostStateStorage(final Path original) throws IOException {
    final ObjectNode fixture = (ObjectNode) MAPPER.readTree(original.toFile());
    final ObjectNode storage =
        (ObjectNode) fixture.elements().next().get("postState").elements().next().get("storage");
    for (int key = 1; key <= 40_000; key++) {
      storage.put(String.format("0x%064x", key), "0x01");
    }
    final Path bigStorage = tempDir.resolve("big-storage.json");
    Files.writeString(bigStorage, MAPPER.writeValueAsString(fixture));
    return bigStorage;
  }

  private static JsonNode result(final Path fixture, final boolean sequential) throws IOException {
    final List<String> args = new ArrayList<>(List.of("--json-array"));
    if (sequential) {
      args.add("--bal-sequential");
    }
    args.add(fixture.toString());
    return MAPPER.readTree(run(args.toArray(String[]::new))).get(0);
  }

  private static String run(final String... args) {
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final EngineTestSubCommand engineTest =
        new EngineTestSubCommand(
            new EvmToolCommand(System.in, new PrintWriter(stdout, true, UTF_8)));
    new CommandLine(engineTest).parseArgs(args);
    engineTest.run();
    return stdout.toString(UTF_8);
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
}
