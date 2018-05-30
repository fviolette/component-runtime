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
package org.talend.sdk.component.proxy.api;

import static lombok.AccessLevel.PRIVATE;

import javax.enterprise.inject.spi.CDI;

import lombok.NoArgsConstructor;

/**
 * Entry point to look-up a service of the proxy.
 *
 * NOTE: ensure to call it only once (you can use a guice provider to look it up lazily but instantiate it once).
 *
 * Note that this is not the recommanded way since it bypasses serialization and endpoint mapping but can
 * save some self requests in some cases.
 */
@NoArgsConstructor(access = PRIVATE)
public final class Services {

    public static ConfigurationTypes configurationTypes() {
        return lookup(ConfigurationTypes.class);
    }

    private static <T> T lookup(final Class<T> api) {
        return CDI.current().select(api).get();
    }
}
