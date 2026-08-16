/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer;

import com.salesforce.revoman.output.postman.PostmanEnvironment;
import com.salesforce.revoman.output.report.TxnInfo;
import org.http4k.core.Request;

public final class JavaEnvironmentAndTxnApiFixture {
  public PostmanEnvironment<Object> environment() {
    PostmanEnvironment<Object> environment = new PostmanEnvironment<>();
    environment.set("token", "value");
    environment.put("count", 1);
    environment.getAsString("token");
    environment.getInt("count");
    environment.immutableEnv();
    environment.postmanEnvJSONFormat();
    environment.mutableEnvCopyWithValuesOfType(String.class);
    environment.mutableEnvCopyWithKeysStartingWith(String.class, "tok");
    environment.mutableEnvCopyWithKeysEndingWith(String.class, "ken");
    environment.mutableEnvCopyWithKeysMatching(String.class, "to.*");
    environment.valuesForKeysStartingWith(String.class, "tok");
    environment.<String>getTypedObj("token", String.class);
    environment.unset("count");
    return environment;
  }

  public String typedTransaction(TxnInfo<Request> info) {
    String value = info.<String>getTypedTxnObj(String.class);
    return value + TxnInfo.getURIPath(info);
  }
}
