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
package org.talend.sdk.component.runtime.input;

import static java.util.stream.Collectors.toList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import org.talend.sdk.component.api.input.Assessor;
import org.talend.sdk.component.api.input.Emitter;
import org.talend.sdk.component.api.input.Split;
import org.talend.sdk.component.runtime.base.Delegated;
import org.talend.sdk.component.runtime.base.LifecycleImpl;
import org.talend.sdk.component.runtime.serialization.ContainerFinder;
import org.talend.sdk.component.runtime.serialization.EnhancedObjectInputStream;

import lombok.AllArgsConstructor;

public class PartitionMapperImpl extends LifecycleImpl implements Mapper, Delegated {

    private static final Object[] NO_ARG = new Object[0];

    private String inputName;

    private boolean stream;

    private transient Method assessor;

    private transient Method split;

    private transient Method inputFactory;

    private transient Function<Long, Object[]> splitArgSupplier;

    public PartitionMapperImpl(final String rootName, final String name, final String inputName, final String plugin,
            final boolean stream, final Serializable instance) {
        super(instance, rootName, name, plugin);
        this.stream = stream;
        this.inputName = inputName;
    }

    protected PartitionMapperImpl() {
        // no-op
    }

    @Override
    public long assess() {
        lazyInit();
        return Number.class.cast(doInvoke(assessor)).longValue();
    }

    @Override
    public List<Mapper> split(final long desiredSize) {
        lazyInit();
        return ((Collection<?>) doInvoke(split, splitArgSupplier.apply(desiredSize)))
                .stream()
                .map(Serializable.class::cast)
                .map(mapper -> new PartitionMapperImpl(rootName(), name(), inputName, plugin(), stream, mapper))
                .collect(toList());
    }

    @Override
    public Input create() {
        lazyInit();
        // note: we can surely mutualize/cache the reflection a bit here but let's wait
        // to see it is useful before doing it,
        // java 7/8 made enough progress to probably make it smooth OOTB
        return new InputImpl(rootName(), inputName, plugin(), Serializable.class.cast(doInvoke(inputFactory)));
    }

    @Override
    public boolean isStream() {
        return stream;
    }

    @Override
    public Object getDelegate() {
        return delegate;
    }

    private void lazyInit() {
        if (assessor == null || split == null || inputFactory == null) {
            inputName = inputName == null || inputName.isEmpty() ? name() : inputName;
            assessor = findMethods(Assessor.class).findFirst().get();
            split = findMethods(Split.class).findFirst().get();
            inputFactory = findMethods(Emitter.class).findFirst().get();

            switch (split.getParameterCount()) {
            case 1:
                if (int.class == split.getParameterTypes()[0]) {
                    splitArgSupplier = desiredSize -> new Object[] { desiredSize.intValue() };
                } else if (long.class == split.getParameterTypes()[0]) {
                    splitArgSupplier = desiredSize -> new Object[] { desiredSize };
                } else {
                    throw new IllegalArgumentException("@PartitionSize only supports int and long");
                }
                break;
            case 0:
            default:
                splitArgSupplier = desiredSize -> NO_ARG;
            }
        }
    }

    Object writeReplace() throws ObjectStreamException {
        return new SerializationReplacer(plugin(), rootName(), name(), inputName, stream, serializeDelegate());
    }

    @AllArgsConstructor
    private static class SerializationReplacer implements Serializable {

        private final String plugin;

        private final String component;

        private final String name;

        private final String input;

        private final boolean stream;

        private final byte[] value;

        Object readResolve() throws ObjectStreamException {
            try {
                return new PartitionMapperImpl(component, name, input, plugin, stream, loadDelegate());
            } catch (final IOException | ClassNotFoundException e) {
                throw new InvalidObjectException(e.getMessage());
            }
        }

        private Serializable loadDelegate() throws IOException, ClassNotFoundException {
            try (final ObjectInputStream ois = new EnhancedObjectInputStream(new ByteArrayInputStream(value),
                    ContainerFinder.Instance.get().find(plugin).classloader())) {
                final Object obj = ois.readObject();
                return Serializable.class.cast(obj);
            }
        }
    }
}
