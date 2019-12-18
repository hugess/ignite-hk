/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.springdata20.repository.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteException;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.springdata20.repository.IgniteRepository;
import org.apache.ignite.springdata20.repository.config.DynamicQueryConfig;
import org.apache.ignite.springdata20.repository.config.Query;
import org.apache.ignite.springdata20.repository.config.RepositoryConfig;
import org.apache.ignite.springdata20.repository.query.IgniteQuery;
import org.apache.ignite.springdata20.repository.query.IgniteQueryGenerator;
import org.apache.ignite.springdata20.repository.query.IgniteRepositoryQuery;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.AbstractEntityInformation;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryMethodEvaluationContextProvider;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Crucial for spring-data functionality class. Create proxies for repositories.
 */
public class IgniteRepositoryFactory extends RepositoryFactorySupport {

    private final ApplicationContext ctx;
    /** Mapping of a repository to a cache. */
    private final Map<Class<?>, String> repoToCache = new HashMap<>();
    /** Mapping of a repository to a ignite instance. */
    private final Map<Class<?>, Ignite> repoToIgnite = new HashMap<>();

    /**
     * Creates the factory with initialized {@link Ignite} instance.
     *
     * @param ctx
     *     the ctx
     */
    public IgniteRepositoryFactory(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    private Ignite igniteForRepoConfig(RepositoryConfig config) {
        try {
            return (Ignite)ctx.getBean(config.igniteInstance());
        } catch (BeansException ex) {
            try {
                IgniteConfiguration cfg = (IgniteConfiguration)ctx.getBean(config.igniteCfg());
                try {
                    // first try to attach to existing ignite instance
                    return Ignition.ignite(cfg.getIgniteInstanceName());
                } catch (Exception e) {
                    // nop
                }
                return Ignition.start(cfg);
            } catch (BeansException ex2) {
                try {
                    String path = (String)ctx.getBean(config.igniteSpringCfgPath());
                    return Ignition.start(path);
                } catch (BeansException ex3) {
                    throw new IgniteException("Failed to initialize Ignite repository factory. Ignite instance or"
                                                  + " IgniteConfiguration or a path to Ignite's spring XML "
                                                  + "configuration must be defined in the"
                                                  + " application configuration");
                }
            }
        }
    }

    /**
     * {@inheritDoc} @param <T>  the type parameter
     *
     * @param <ID>
     *     the type parameter
     * @param domainClass
     *     the domain class
     * @return the entity information
     */
    @Override
    public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
        return new AbstractEntityInformation<T, ID>(domainClass) {
            @Override
            public ID getId(T entity) {
                return null;
            }

            @Override
            public Class<ID> getIdType() {
                return null;
            }
        };
    }

    /**
     * {@inheritDoc} @param metadata the metadata
     *
     * @return the repository base class
     */
    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
        return IgniteRepositoryImpl.class;
    }

    /**
     * {@inheritDoc} @param repoItf the repo itf
     *
     * @return the repository metadata
     */
    @Override
    protected synchronized RepositoryMetadata getRepositoryMetadata(Class<?> repoItf) {
        Assert.notNull(repoItf, "Repository interface must be set.");
        Assert.isAssignable(IgniteRepository.class, repoItf, "Repository must implement IgniteRepository interface.");

        RepositoryConfig annotation = repoItf.getAnnotation(RepositoryConfig.class);

        Assert.notNull(annotation, "Set a name of an Apache Ignite cache using @RepositoryConfig annotation to map "
                                       + "this repository to the underlying cache.");

        Assert.hasText(annotation.cacheName(), "Set a name of an Apache Ignite cache using @RepositoryConfig "
                                                   + "annotation to map this repository to the underlying cache.");

        repoToCache.put(repoItf, annotation.cacheName());

        repoToIgnite.put(repoItf, igniteForRepoConfig(annotation));

        return super.getRepositoryMetadata(repoItf);
    }

    /* control underline cache creation to avoid incorrect cache creation */
    private IgniteCache getRepositoryCache(Class<?> repoIf) {
        Ignite ignite = repoToIgnite.get(repoIf);

        RepositoryConfig config = repoIf.getAnnotation(RepositoryConfig.class);

        String cacheName = repoToCache.get(repoIf);

        IgniteCache c = config.autoCreateCache() ? ignite.getOrCreateCache(cacheName) : ignite.cache(cacheName);

        if (c == null) {
            throw new IllegalStateException(
                "Cache '" + cacheName + "' not found for repository interface " + repoIf.getName()
                    + ". Please, add a cache configuration to ignite configuration"
                    + " or pass autoCreateCache=true to org.apache.ignite.springdata20"
                    + ".repository.config.RepositoryConfig annotation.");
        }

        return c;
    }

    /**
     * {@inheritDoc} @param metadata the metadata
     *
     * @return the target repository
     */
    @Override
    protected Object getTargetRepository(RepositoryInformation metadata) {
        Ignite ignite = repoToIgnite.get(metadata.getRepositoryInterface());
        return getTargetRepositoryViaReflection(metadata, ignite,
            getRepositoryCache(metadata.getRepositoryInterface()));
    }

    /**
     * {@inheritDoc} @param key the key
     *
     * @param evaluationContextProvider
     *     the evaluation context provider
     * @return the query lookup strategy
     */
    @Override
    protected Optional<QueryLookupStrategy> getQueryLookupStrategy(final QueryLookupStrategy.Key key,
        QueryMethodEvaluationContextProvider evaluationContextProvider) {
        return Optional.of((mtd, metadata, factory, namedQueries) -> {

            final Query annotation = mtd.getAnnotation(Query.class);
            final Ignite ignite = repoToIgnite.get(metadata.getRepositoryInterface());

            if (annotation != null && (StringUtils.hasText(annotation.value()) || annotation.textQuery() || annotation
                                                                                                                .dynamicQuery())) {

                String qryStr = annotation.value();
                boolean annotatedIgniteQuery = !annotation.dynamicQuery() && (StringUtils.hasText(qryStr)
                                                                                        || annotation.textQuery());
                IgniteQuery query = annotatedIgniteQuery ? new IgniteQuery(qryStr,
                    !annotation.textQuery() && (isFieldQuery(qryStr) || annotation.forceFieldsQuery()),
                    annotation.textQuery(), false, IgniteQueryGenerator.getOptions(mtd)) : null;
                if (key != QueryLookupStrategy.Key.CREATE) {
                    return new IgniteRepositoryQuery(ignite, metadata, query, mtd, factory,
                        getRepositoryCache(metadata.getRepositoryInterface()),
                        annotatedIgniteQuery ? DynamicQueryConfig.fromQueryAnnotation(annotation) : null,
                        evaluationContextProvider);
                }
            }

            if (key == QueryLookupStrategy.Key.USE_DECLARED_QUERY) {
                throw new IllegalStateException("To use QueryLookupStrategy.Key.USE_DECLARED_QUERY, pass "
                                                    + "a query string via org.apache.ignite.springdata.repository"
                                                    + ".config.Query annotation.");
            }

            return new IgniteRepositoryQuery(ignite, metadata, IgniteQueryGenerator.generateSql(mtd, metadata), mtd,
                factory, getRepositoryCache(metadata.getRepositoryInterface()),
                DynamicQueryConfig.fromQueryAnnotation(annotation), evaluationContextProvider);
        });
    }

    /**
     * Is field query boolean.
     *
     * @param qry
     *     Query string.
     * @return {@code true} if query is SQLFieldsQuery.
     */
    public static boolean isFieldQuery(String qry) {
        return (qry.matches("(?i)^SELECT.*") && !qry.matches("(?i)^SELECT\\s+(?:\\w+\\.)?+\\*.*"));
    }

}
