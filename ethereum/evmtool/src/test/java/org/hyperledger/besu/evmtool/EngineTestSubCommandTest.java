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
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.PayloadStatusV1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EngineTestSubCommandTest {

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

  private static JsonRpcResponse forkchoiceStatus(final EngineStatus status) {
    return new JsonRpcSuccessResponse(
        null, new ForkchoiceUpdatedResultV1(new PayloadStatusV1(status)));
  }

  private static String forkchoiceFailure(final JsonRpcResponse response) {
    final ExecutionEngineJsonRpcMethod forkchoiceUpdated = mock(ExecutionEngineJsonRpcMethod.class);
    when(forkchoiceUpdated.syncResponse(any())).thenReturn(response);
    return EngineTestSubCommand.forkchoiceFailure(forkchoiceUpdated, 4, Hash.ZERO);
  }
}
