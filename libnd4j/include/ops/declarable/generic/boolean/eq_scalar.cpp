/* ******************************************************************************
 *
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

//
// Created by raver119 on 13.10.2017.
//

#include <system/op_boilerplate.h>
#if NOT_EXCLUDED(OP_eq_scalar)

#include <ops/declarable/CustomOperations.h>

namespace sd {
namespace ops {
BOOLEAN_OP_IMPL(eq_scalar, 2, true) {
  auto x = INPUT_VARIABLE(0);
  auto y = INPUT_VARIABLE(1);

  if (x->e<double>(0) == y->e<double>(0))
    return sd::Status::TRUE;
  else
    return sd::Status::FALSE;
}
DECLARE_SYN(Equals, eq_scalar);
// DECLARE_SYN(equals, eq_scalar);

DECLARE_TYPES(eq_scalar) {
  getOpDescriptor()
      ->setAllowedInputTypes(0, DataType::ANY)
      ->setAllowedInputTypes(1, DataType::ANY)
      ->setAllowedOutputTypes(0, DataType::BOOL);
}
}  // namespace ops
}  // namespace sd

#endif
