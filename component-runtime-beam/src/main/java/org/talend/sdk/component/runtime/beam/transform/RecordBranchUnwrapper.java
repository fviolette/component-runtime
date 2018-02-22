/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.talend.sdk.component.runtime.beam.transform;

import javax.json.JsonArray;
import javax.json.JsonBuilderFactory;
import javax.json.JsonObject;
import javax.json.JsonValue;

import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PCollection;
import org.talend.sdk.component.runtime.beam.coder.JsonpJsonObjectCoder;
import org.talend.sdk.component.runtime.beam.transform.service.ServiceLookup;
import org.talend.sdk.component.runtime.manager.ComponentManager;

import lombok.AllArgsConstructor;

/**
 * Extract the value of a branch if exists (unwrap).
 */
@AllArgsConstructor
public class RecordBranchUnwrapper extends DoFn<JsonObject, JsonObject> {

    private JsonBuilderFactory factory;

    private String branch;

    protected RecordBranchUnwrapper() {
        // no-op
    }

    @ProcessElement
    public void onElement(final ProcessContext context) {
        final JsonObject aggregate = context.element();
        if (aggregate.containsKey(branch)) {
            final JsonArray jsonArray = aggregate.getJsonArray(branch);
            if (!jsonArray.isEmpty()) {
                final JsonValue next = jsonArray.iterator().next();
                if (next != JsonValue.NULL) {
                    context.output(next.asJsonObject());
                }
            }
        }
    }

    public static PTransform<PCollection<JsonObject>, PCollection<JsonObject>> of(final String plugin,
            final String branchSelector) {
        final JsonBuilderFactory lookup =
                ServiceLookup.lookup(ComponentManager.instance(), plugin, JsonBuilderFactory.class);
        return new JsonObjectParDoTransformCoderProvider<>(JsonpJsonObjectCoder.of(plugin),
                new RecordBranchUnwrapper(lookup, branchSelector));
    }
}
