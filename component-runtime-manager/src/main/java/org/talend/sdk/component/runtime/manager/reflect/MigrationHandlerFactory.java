/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.talend.sdk.component.runtime.manager.reflect;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Stream.concat;
import static org.talend.sdk.component.runtime.base.lang.exception.InvocationExceptionWrapper.toRuntimeException;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import org.talend.sdk.component.api.component.MigrationHandler;
import org.talend.sdk.component.api.component.Version;
import org.talend.sdk.component.runtime.manager.ComponentManager;
import org.talend.sdk.component.runtime.manager.ParameterMeta;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class MigrationHandlerFactory {

    private static final MigrationHandler NO_MIGRATION = (incomingVersion, incomingData) -> incomingData;

    private final ReflectionService reflections;

    public MigrationHandler findMigrationHandler(final List<ParameterMeta> parameterMetas, final Class<?> type,
            final ComponentManager.AllServices services) {

        final MigrationHandler implicitMigrationHandler = ofNullable(parameterMetas)
                .map(Collection::stream)
                .orElseGet(Stream::empty)
                .flatMap(this::getNestedConfigType)
                .sorted(Comparator.comparingInt(o -> o.getPath().length()))
                .map(p -> {
                    // for now we can assume it is not in arrays
                    final MigrationHandler handler =
                            findMigrationHandler(emptyList(), Class.class.cast(p.getJavaType()), services);
                    if (handler == NO_MIGRATION) {
                        return null;
                    }

                    return (Function<Map<String, String>, Map<String, String>>) map -> buildMigrationFunction(p,
                            handler, p.getPath(), map,
                            ofNullable(((Class) p.getJavaType()).getAnnotation(Version.class))
                                    .map(v -> ((Version) v).value())
                                    .orElse(-1));
                })
                .filter(Objects::nonNull)
                .reduce(NO_MIGRATION,
                        (current,
                                partial) -> (incomingVersion, incomingData) -> current.migrate(incomingVersion,
                                        partial.apply(incomingData)),
                        (migrationHandler, migrationHandler2) -> (incomingVersion, incomingData) -> migrationHandler2
                                .migrate(incomingVersion, migrationHandler.migrate(incomingVersion, incomingData)));

        return ofNullable(type.getAnnotation(Version.class))
                .map(Version::migrationHandler)
                .filter(t -> t != MigrationHandler.class)
                .flatMap(t -> Stream.of(t.getConstructors()).min(
                        (o1, o2) -> o2.getParameterCount() - o1.getParameterCount()))
                .map(t -> services.getServices().computeIfAbsent(t.getDeclaringClass(), k -> {
                    try {
                        return t.newInstance(
                                reflections.parameterFactory(t, services.getServices(), null).apply(emptyMap()));
                    } catch (final InstantiationException | IllegalAccessException e) {
                        throw new IllegalArgumentException(e);
                    } catch (final InvocationTargetException e) {
                        throw toRuntimeException(e);
                    }
                }))
                .map(MigrationHandler.class::cast)
                .map(h -> {
                    if (implicitMigrationHandler == NO_MIGRATION) {
                        return h;
                    }
                    return (MigrationHandler) (incomingVersion, incomingData) -> h.migrate(incomingVersion,
                            implicitMigrationHandler.migrate(incomingVersion, incomingData));
                })
                .orElse(implicitMigrationHandler);
    }

    private Stream<ParameterMeta> getNestedConfigType(final ParameterMeta parameterMeta) {
        return concat(
                (parameterMeta.getJavaType() instanceof Class && parameterMeta.getMetadata().keySet().stream().anyMatch(
                        k -> k.startsWith("tcomp::configurationtype::"))) ? Stream.of(parameterMeta) : Stream.empty(),
                ofNullable(parameterMeta.getNestedParameters())
                        .map(Collection::stream)
                        .orElseGet(Stream::empty)
                        .flatMap(this::getNestedConfigType));
    }

    private Map<String, String> buildMigrationFunction(final ParameterMeta p, final MigrationHandler handler,
            final String prefix, final Map<String, String> map, final Integer currentVersion) {
        final String versionPath = String.format("%s.__version", prefix);
        final String version = map.get(versionPath);
        final Map<String, String> result = new HashMap<>(map);
        if (version != null && Integer.parseInt(version.trim()) < currentVersion) {
            final Map<String, String> toMigrate = map
                    .entrySet()
                    .stream()
                    .filter(e -> e.getKey().startsWith(prefix + '.') && !e.getKey().endsWith(".__version"))
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            toMigrate.keySet().forEach(result::remove);
            final Map<String, String> migrated = ofNullable(handler.migrate(Integer.parseInt(version.trim()),
                    toMigrate.entrySet().stream().collect(
                            toMap(e -> e.getKey().substring(prefix.length() + 1), Map.Entry::getValue))))
                                    .orElseGet(Collections::emptyMap);
            result.putAll(
                    migrated.entrySet().stream().collect(toMap(e -> prefix + '.' + e.getKey(), Map.Entry::getValue)));
            result.put(versionPath, currentVersion.toString());
        } else {
            log.debug("No version for {} so skipping any potential migration", p.getJavaType().toString());
        }
        return result;
    }
}
