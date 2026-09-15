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
package org.hyperledger.besu.ethereum.mainnet.systemcall;

/**
 * Thrown when a system call runs but does not execute to completion, because it reverted or halted
 * exceptionally.
 *
 * <p>Callers that must check the outcome, such as the EIP-7685 request contracts, let this
 * propagate so the block is rejected. Callers of an unchecked system call, such as the EIP-4788
 * beacon roots and EIP-2935 history contracts, catch it and continue: the call's state changes are
 * discarded but the block stays valid.
 */
public class SystemCallFailedException extends RuntimeException {
  public SystemCallFailedException(final String message) {
    super(message);
  }
}
