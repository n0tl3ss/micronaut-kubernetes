/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.kubernetes.client.informer;

import io.kubernetes.client.Discovery;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.util.Namespaces;
import io.micronaut.aop.ConstructorInterceptor;
import io.micronaut.aop.ConstructorInvocationContext;
import io.micronaut.aop.InterceptorBean;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.kubernetes.client.DiscoveryCache;
import io.micronaut.kubernetes.client.NamespaceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Constructor interceptor for the {@link ResourceEventHandler} beans annotated by {@link Informer} annotation that
 * based on provided parameters in the {@link Informer} annotation created the {@link SharedIndexInformer} and registers
 * {@link ResourceEventHandler} to the informer.
 *
 * @param <ApiType> type of Kubernetes Object
 * @author Pavol Gressa
 * @since 3.1
 */
@Requires(beans = SharedInformerFactory.class)
@InterceptorBean(Informer.class)
@Internal
public class ResourceEventHandlerConstructorInterceptor<ApiType extends KubernetesObject> implements ConstructorInterceptor<ResourceEventHandler<ApiType>> {

    private static final Logger LOG = LoggerFactory.getLogger(ResourceEventHandlerConstructorInterceptor.class);

    private final SharedIndexInformerFactory sharedIndexInformerFactory;
    private final NamespaceResolver namespaceResolver;
    private final ApplicationContext applicationContext;
    private final DiscoveryCache discoveryCache;

    public ResourceEventHandlerConstructorInterceptor(SharedIndexInformerFactory sharedIndexInformerFactory,
                                                      NamespaceResolver namespaceResolver,
                                                      ApplicationContext applicationContext,
                                                      @Nullable DiscoveryCache discoveryCache) {
        this.sharedIndexInformerFactory = sharedIndexInformerFactory;
        this.namespaceResolver = namespaceResolver;
        this.applicationContext = applicationContext;
        this.discoveryCache = discoveryCache;
    }

    @Override
    public @NonNull
    ResourceEventHandler<ApiType> intercept(@NonNull ConstructorInvocationContext<ResourceEventHandler<ApiType>> context) {
        final Class<ResourceEventHandler<ApiType>> declaringType = context.getDeclaringType();

        if (declaringType.isAnnotationPresent(Informer.class)) {
            Informer typeAnnotation = declaringType.getAnnotation(Informer.class);

            if (LOG.isTraceEnabled()) {
                LOG.trace("Found @Informer annotation on {}", declaringType);
            }

            // resolve namespaces to watch
            List<String> namespaces = new ArrayList<>();

            String[] namespacesArr = typeAnnotation.namespaces();
            Collections.addAll(namespaces, namespacesArr);

            if (!Objects.equals(typeAnnotation.namespacesSupplier(), EmptyNamespacesSupplier.class)) {
                Class<? extends Supplier<String[]>> namespaceSupplierClass = typeAnnotation.namespacesSupplier();
                Supplier<String[]> supplierBean = applicationContext.getBean(namespaceSupplierClass);
                Collections.addAll(namespaces, supplierBean.get());
            }

            String namespace = typeAnnotation.namespace();
            if (!namespace.equals(Informer.RESOLVE_AUTOMATICALLY)) {
                namespaces.add(namespace);
            }

            if (namespace.equals(Informer.RESOLVE_AUTOMATICALLY) && namespaces.isEmpty()) {
                namespaces.add(namespaceResolver.resolveNamespace());
            }

            if (namespaces.contains(Informer.ALL_NAMESPACES)) {
                namespaces = Collections.singletonList(Namespaces.NAMESPACE_ALL);
            }

            // resolve label selector
            String labelSelector = null;
            if (!Objects.equals(typeAnnotation.labelSelector(), "")) {
                labelSelector = typeAnnotation.labelSelector();
            }

            if (!Objects.equals(typeAnnotation.labelSelectorSupplier(), EmptyLabelSupplier.class)) {
                Class<? extends Supplier<String>> selectorSupplierClass = typeAnnotation.labelSelectorSupplier();
                Supplier<String> supplierBean = applicationContext.getBean(selectorSupplierClass);
                labelSelector = labelSelector == null ? supplierBean.get() : labelSelector + "," + supplierBean.get();
            }

            // resolve resourcePlural and apiGroup
            String resourcePlural = typeAnnotation.resourcePlural();
            String apiGroup = typeAnnotation.apiGroup();

            // use discovery to resolve the api group and/or resource plural when they are missing
            if (resourcePlural.equals(Informer.RESOLVE_AUTOMATICALLY) || apiGroup.equals(Informer.RESOLVE_AUTOMATICALLY)) {

                if (discoveryCache == null) {
                    throw new IllegalArgumentException("The discovery cache is disabled, provide `resourcePlural` and `apiGroup`" +
                            " parameters to create shared informer.");
                }

                Optional<Discovery.APIResource> apiResourceOptional = discoveryCache.find(typeAnnotation.apiType());
                if (apiResourceOptional.isPresent()) {
                    Discovery.APIResource apiResource = apiResourceOptional.get();
                    if (apiGroup.equals(Informer.RESOLVE_AUTOMATICALLY)) {
                        apiGroup = apiResource.getGroup();
                    }

                    if (resourcePlural.equals(Informer.RESOLVE_AUTOMATICALLY)) {
                        resourcePlural = apiResource.getResourcePlural();
                    }

                    if (apiResource.getNamespaced() != null && !apiResource.getNamespaced()) {
                        namespaces = Collections.singletonList(Namespaces.NAMESPACE_ALL);
                    }
                } else {
                    throw new IllegalArgumentException("Failed to resolve `resourcePlural` and/or `apiGroup`" +
                            " for " + typeAnnotation.apiType() + " from discovery cache.");
                }
            }

            List<SharedIndexInformer<? extends KubernetesObject>> informers = sharedIndexInformerFactory.sharedIndexInformersFor(
                    typeAnnotation.apiType(),
                    typeAnnotation.apiListType(),
                    resourcePlural,
                    apiGroup,
                    namespaces,
                    labelSelector,
                    typeAnnotation.resyncCheckPeriod(),
                    true);

            ResourceEventHandler resourceEventHandler = context.proceed();
            informers.forEach(i -> i.addEventHandler(resourceEventHandler));
            return resourceEventHandler;
        } else {
            if (LOG.isErrorEnabled()) {
                LOG.error("Failed to create informer for the class [{}] that implements ResourceEventHandler. " +
                        "The io.micronaut.kubernetes.informer.@Informer annotation is missing.", declaringType.getName());
            }
        }
        return context.proceed();
    }
}
