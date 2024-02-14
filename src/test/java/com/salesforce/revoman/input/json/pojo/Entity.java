/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json.pojo;

import java.util.Map;

public class Entity {
  private final Map<String, Object> fields;

  public Entity(Map<String, Object> fields) {
    this.fields = fields;
  }

  public Map<String, Object> getFields() {
    return fields;
  }
}
