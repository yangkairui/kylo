package com.thinkbiganalytics.feedmgr.service.datasource;

/*-
 * #%L
 * kylo-feed-manager-controller
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.thinkbiganalytics.metadata.api.datasource.DatasourceProvider;
import com.thinkbiganalytics.metadata.rest.model.data.Datasource;
import com.thinkbiganalytics.metadata.rest.model.data.DerivedDatasource;
import com.thinkbiganalytics.metadata.rest.model.data.JdbcDatasource;
import com.thinkbiganalytics.metadata.rest.model.data.UserDatasource;
import com.thinkbiganalytics.metadata.rest.model.feed.Feed;
import com.thinkbiganalytics.nifi.rest.client.NiFiControllerServicesRestClient;
import com.thinkbiganalytics.nifi.rest.client.NiFiRestClient;
import com.thinkbiganalytics.nifi.rest.client.NifiClientRuntimeException;
import com.thinkbiganalytics.nifi.rest.client.NifiComponentNotFoundException;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.web.api.dto.ControllerServiceDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Transform {@code Datasource}s between domain and REST objects.
 */
public class DatasourceModelTransform {

    private static final Logger log = LoggerFactory.getLogger(DatasourceModelTransform.class);

    /**
     * Level of detail to include when transforming objects.
     */
    public enum Level {
        /**
         * Include sensitive fields in result
         */
        ADMIN,

        /**
         * Include everything except sensitive fields in result
         */
        FULL,

        /**
         * Include basic field and connections in result
         */
        CONNECTIONS,

        /**
         * Include only basic fields in result
         */
        BASIC
    }

    /**
     * Provides access to {@code Datasource} domain objects
     */
    @Nonnull
    private final DatasourceProvider datasourceProvider;

    /**
     * Encrypts strings
     */
    @Nonnull
    private final TextEncryptor encryptor;

    /**
     * NiFi REST client
     */
    private final NiFiRestClient nifiRestClient;

    /**
     * Constructs a {@code DatasourceModelTransform}.
     *
     * @param datasourceProvider the {@code Datasource} domain object provider
     * @param encryptor          the text encryptor
     * @param nifiRestClient     the NiFi REST client
     */
    public DatasourceModelTransform(@Nonnull final DatasourceProvider datasourceProvider, @Nonnull final TextEncryptor encryptor, @Nonnull final NiFiRestClient nifiRestClient) {
        this.datasourceProvider = datasourceProvider;
        this.encryptor = encryptor;
        this.nifiRestClient = nifiRestClient;
    }

    /**
     * Transforms the specified domain object to a REST object.
     *
     * @param domain the domain object
     * @param level  the level of detail
     * @return the REST object
     */
    public Datasource toDatasource(@Nonnull final com.thinkbiganalytics.metadata.api.datasource.Datasource domain, @Nonnull final Level level) {
        if (domain instanceof com.thinkbiganalytics.metadata.api.datasource.DerivedDatasource) {
            final DerivedDatasource ds = new DerivedDatasource();
            updateDatasource(ds, (com.thinkbiganalytics.metadata.api.datasource.DerivedDatasource) domain, level);
            return ds;
        } else if (domain instanceof com.thinkbiganalytics.metadata.api.datasource.JdbcDatasource) {
            final JdbcDatasource jdbcDatasource = new JdbcDatasource();
            updateDatasource(jdbcDatasource, (com.thinkbiganalytics.metadata.api.datasource.JdbcDatasource) domain, level);
            return jdbcDatasource;
        } else {
            throw new IllegalArgumentException("Not a supported datasource class: " + domain.getClass());
        }
    }

    /**
     * Transforms the specified REST object to a domain object.
     *
     * @param ds the REST object
     * @return the domain object
     */
    public com.thinkbiganalytics.metadata.api.datasource.Datasource toDomain(@Nonnull final Datasource ds) {
        if (ds instanceof JdbcDatasource) {
            final com.thinkbiganalytics.metadata.api.datasource.JdbcDatasource domain;
            if (ds.getId() != null) {
                final com.thinkbiganalytics.metadata.api.datasource.Datasource.ID id = datasourceProvider.resolve(ds.getId());
                domain = (com.thinkbiganalytics.metadata.api.datasource.JdbcDatasource) datasourceProvider.getDatasource(id);
            } else {
                domain = datasourceProvider.ensureDatasource(ds.getName(), ds.getDescription(), com.thinkbiganalytics.metadata.api.datasource.JdbcDatasource.class);
                ds.setId(domain.getId().toString());
            }
            updateDomain(domain, (JdbcDatasource) ds);
            return domain;
        } else {
            throw new IllegalArgumentException("Not a supported user datasource class: " + ds.getClass());
        }
    }

    /**
     * Updates the specified REST object with properties from the specified domain object.
     *
     * @param ds     the REST object
     * @param domain the domain object
     * @param level  the level of detail
     */
    private void updateDatasource(@Nonnull final Datasource ds, @Nonnull final com.thinkbiganalytics.metadata.api.datasource.Datasource domain, @Nonnull final Level level) {
        ds.setId(domain.getId().toString());
        ds.setDescription(domain.getDescription());
        ds.setName(domain.getName());

        // Add connections if level matches
        if (level.compareTo(Level.CONNECTIONS) <= 0) {
            for (com.thinkbiganalytics.metadata.api.feed.FeedSource domainSrc : domain.getFeedSources()) {
                Feed feed = new Feed();
                feed.setId(domainSrc.getFeed().getId().toString());
                feed.setSystemName(domainSrc.getFeed().getName());

                ds.getSourceForFeeds().add(feed);
            }
            for (com.thinkbiganalytics.metadata.api.feed.FeedDestination domainDest : domain.getFeedDestinations()) {
                Feed feed = new Feed();
                feed.setId(domainDest.getFeed().getId().toString());
                feed.setSystemName(domainDest.getFeed().getName());

                ds.getDestinationForFeeds().add(feed);
            }
        }
    }

    /**
     * Updates the specified REST object with properties from the specified domain object.
     *
     * @param ds     the REST object
     * @param domain the domain object
     * @param level  the level of detail
     */
    public void updateDatasource(@Nonnull final DerivedDatasource ds, @Nonnull final com.thinkbiganalytics.metadata.api.datasource.DerivedDatasource domain, @Nonnull final Level level) {
        updateDatasource((Datasource) ds, domain, level);
        ds.setProperties(domain.getProperties());
        ds.setDatasourceType(domain.getDatasourceType());
    }

    /**
     * Updates the specified REST object with properties from the specified domain object.
     *
     * @param ds     the REST object
     * @param domain the domain object
     * @param level  the level of detail
     */
    private void updateDatasource(@Nonnull final JdbcDatasource ds, @Nonnull final com.thinkbiganalytics.metadata.api.datasource.JdbcDatasource domain, @Nonnull final Level level) {
        updateDatasource((UserDatasource) ds, domain, level);
        domain.getControllerServiceId().ifPresent(ds::setControllerServiceId);
        if (level.compareTo(Level.ADMIN) <= 0) {
            ds.setPassword(encryptor.decrypt(domain.getPassword()));
        }
        if (level.compareTo(Level.FULL) <= 0) {
            // Fetch database properties from NiFi
            domain.getControllerServiceId()
                .flatMap(id -> nifiRestClient.controllerServices().findById(id))
                .ifPresent(controllerService -> {
                    ds.setDatabaseConnectionUrl(controllerService.getProperties().get(DatasourceConstants.DATABASE_CONNECTION_URL));
                    ds.setDatabaseDriverClassName(controllerService.getProperties().get(DatasourceConstants.DATABASE_DRIVER_CLASS_NAME));
                    ds.setDatabaseDriverLocation(controllerService.getProperties().get(DatasourceConstants.DATABASE_DRIVER_LOCATION));
                    ds.setDatabaseUser(controllerService.getProperties().get(DatasourceConstants.DATABASE_USER));
                });
        }
    }

    /**
     * Updates the specified REST object with properties from the specified domain object.
     *
     * @param ds     the REST object
     * @param domain the domain object
     * @param level  the level of detail
     */
    private void updateDatasource(@Nonnull final UserDatasource ds, @Nonnull final com.thinkbiganalytics.metadata.api.datasource.UserDatasource domain, @Nonnull final Level level) {
        updateDatasource((Datasource) ds, domain, level);
        ds.setType(domain.getType());
    }

    /**
     * Updates the specified domain object with properties from the specified REST object.
     *
     * @param domain the domain object
     * @param ds     the REST object
     */
    private void updateDomain(@Nonnull final com.thinkbiganalytics.metadata.api.datasource.JdbcDatasource domain, @Nonnull final JdbcDatasource ds) {
        updateDomain(domain, (UserDatasource) ds);

        // Look for changed properties
        final Map<String, String> properties = new HashMap<>();
        if (StringUtils.isNotBlank(ds.getDatabaseConnectionUrl())) {
            properties.put(DatasourceConstants.DATABASE_CONNECTION_URL, ds.getDatabaseConnectionUrl());
        }
        if (StringUtils.isNotBlank(ds.getDatabaseDriverClassName())) {
            properties.put(DatasourceConstants.DATABASE_DRIVER_CLASS_NAME, ds.getDatabaseDriverClassName());
        }
        if (StringUtils.isNotBlank(ds.getDatabaseDriverLocation())) {
            properties.put(DatasourceConstants.DATABASE_DRIVER_LOCATION, ds.getDatabaseDriverLocation());
        }
        if (StringUtils.isNotBlank(ds.getDatabaseUser())) {
            properties.put(DatasourceConstants.DATABASE_USER, ds.getDatabaseUser());
        }
        if (StringUtils.isNotBlank(ds.getPassword())) {
            domain.setPassword(encryptor.encrypt(ds.getPassword()));
            properties.put(DatasourceConstants.PASSWORD, ds.getPassword());
        }

        // Update or create the controller service
        ControllerServiceDTO controllerService = null;

        if (domain.getControllerServiceId().isPresent()) {
            controllerService = new ControllerServiceDTO();
            controllerService.setId(domain.getControllerServiceId().get());
            controllerService.setName(ds.getName());
            controllerService.setComments(ds.getDescription());
            controllerService.setProperties(properties);
            try {
                nifiRestClient.controllerServices().updateStateById(controllerService.getId(), NiFiControllerServicesRestClient.State.DISABLED);
                nifiRestClient.controllerServices().update(controllerService);
                nifiRestClient.controllerServices().updateStateById(controllerService.getId(), NiFiControllerServicesRestClient.State.ENABLED);
            } catch (final NifiComponentNotFoundException e) {
                log.warn("Controller service is missing for datasource: {}", domain.getId(), e);
                controllerService = null;
            }
            ds.setControllerServiceId(controllerService.getId());
        }
        if (controllerService == null) {
            controllerService = new ControllerServiceDTO();
            controllerService.setType("org.apache.nifi.dbcp.DBCPConnectionPool");
            controllerService.setName(ds.getName());
            controllerService.setComments(ds.getDescription());
            controllerService.setProperties(properties);
            final ControllerServiceDTO newControllerService = nifiRestClient.controllerServices().create(controllerService);
            try {
                nifiRestClient.controllerServices().updateStateById(newControllerService.getId(), NiFiControllerServicesRestClient.State.ENABLED);
            } catch (final NifiClientRuntimeException nifiException) {
                log.error("Failed to enable controller service for datasource: {}", domain.getId(), nifiException);
                nifiRestClient.controllerServices().disableAndDeleteAsync(newControllerService.getId());
                throw nifiException;
            }
            domain.setControllerServiceId(newControllerService.getId());
            ds.setControllerServiceId(newControllerService.getId());
        }
    }

    /**
     * Updates the specified domain object with properties from the specified REST object.
     *
     * @param domain the domain object
     * @param ds     the REST object
     */
    private void updateDomain(@Nonnull final com.thinkbiganalytics.metadata.api.datasource.UserDatasource domain, @Nonnull final UserDatasource ds) {
        domain.setDescription(ds.getDescription());
        domain.setName(ds.getName());
        domain.setType(ds.getType());
    }
}