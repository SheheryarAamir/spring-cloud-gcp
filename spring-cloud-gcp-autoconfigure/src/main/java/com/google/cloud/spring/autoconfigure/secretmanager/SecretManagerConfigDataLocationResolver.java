/*
 * Copyright 2022-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spring.autoconfigure.secretmanager;

import static com.google.cloud.spring.secretmanager.SecretManagerSyntaxUtils.getMatchedPrefixes;
import static com.google.cloud.spring.secretmanager.SecretManagerSyntaxUtils.warnIfUsingDeprecatedSyntax;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.spring.autoconfigure.core.GcpProperties;
import com.google.cloud.spring.core.DefaultCredentialsProvider;
import com.google.cloud.spring.core.DefaultGcpProjectIdProvider;
import com.google.cloud.spring.core.GcpProjectIdProvider;
import com.google.cloud.spring.core.UserAgentHeaderProvider;
import com.google.cloud.spring.secretmanager.SecretManagerTemplate;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.arrow.util.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.BootstrapRegistry;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;

public class SecretManagerConfigDataLocationResolver implements
    ConfigDataLocationResolver<SecretManagerConfigDataResource> {

  private static final Logger logger = LoggerFactory.getLogger(SecretManagerConfigDataLocationResolver.class);

  /**
   * A static client to avoid creating another client after refreshing.
   */
  private static SecretManagerServiceClient secretManagerServiceClient;

  /**
   * Checks if the property can be resolved by the Secret Manager resolver.
   * For the check, we rely on the presence of the SecretManagerSyntaxUtils class, which is an
   * optional dependency.
   * Since optional dependencies may not be present at runtime, we explicitly check for its
   * existence before resolving the property.
   * If it's not present, it means this config resolver is not meant to be used.
   *
   * @return true if it contains the expected `sm@` or `sm://` prefix, false otherwise.
   */
  @Override
  public boolean isResolvable(ConfigDataLocationResolverContext context,
      ConfigDataLocation location) {
    boolean secretManagerSyntaxUtilsPresent = isClassPresent("com.google.cloud.spring.secretmanager.SecretManagerSyntaxUtils");
    if (!secretManagerSyntaxUtilsPresent) {
      return false;
    }
    Optional<String> matchedPrefix = getMatchedPrefixes(location::hasPrefix);
    warnIfUsingDeprecatedSyntax(logger, matchedPrefix.orElse(""));
    return matchedPrefix.isPresent();
  }

  /**
   * Checks if the specified class is present in this runtime.
   *
   * @param clazzFullName the full name of the class for the existence check
   * @return true if present
   */
  private boolean isClassPresent(String clazzFullName) {
    try {
      Class.forName(clazzFullName);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Override
  public List<SecretManagerConfigDataResource> resolve(ConfigDataLocationResolverContext context,
      ConfigDataLocation location)
      throws ConfigDataLocationNotFoundException, ConfigDataResourceNotFoundException {
    registerSecretManagerBeans(context);

    return Collections.singletonList(
        new SecretManagerConfigDataResource(location));
  }

  private static void registerSecretManagerBeans(ConfigDataLocationResolverContext context) {
    // Register the Core properties.
    registerBean(context, GcpProperties.class, getGcpProperties(context));
    // Register the Secret Manager properties.
    registerBean(
        context, GcpSecretManagerProperties.class, getSecretManagerProperties(context));
    // Register the Secret Manager client.
    registerAndPromoteBean(
        context,
        SecretManagerServiceClient.class,
        // lazy register the client solely for unit test.
        BootstrapRegistry.InstanceSupplier.from(() -> createSecretManagerClient(context)));
    // Register the GCP Project ID provider.
    registerBean(context, GcpProjectIdProvider.class, createProjectIdProvider(context));
    // Register the Secret Manager template.
    registerAndPromoteBean(
        context,
        SecretManagerTemplate.class,
        BootstrapRegistry.InstanceSupplier.of(createSecretManagerTemplate(context)));
  }

  private static GcpProperties getGcpProperties(ConfigDataLocationResolverContext context) {
    return context
        .getBinder()
        .bind(GcpProperties.CORE_PROPERTY_PREFIX, GcpProperties.class)
        .orElse(new GcpProperties());
  }

  private static GcpSecretManagerProperties getSecretManagerProperties(
      ConfigDataLocationResolverContext context) {
    return context.getBinder()
        .bind(GcpSecretManagerProperties.PREFIX, GcpSecretManagerProperties.class)
        .orElse(new GcpSecretManagerProperties());
  }

  @VisibleForTesting
  static GcpProjectIdProvider createProjectIdProvider(ConfigDataLocationResolverContext context) {
    ConfigurableBootstrapContext bootstrapContext = context.getBootstrapContext();
    GcpSecretManagerProperties secretManagerProperties =
        bootstrapContext.get(GcpSecretManagerProperties.class);
    if (secretManagerProperties.getProjectId() != null) {
      return secretManagerProperties::getProjectId;
    }
    GcpProperties gcpProperties = bootstrapContext.get(GcpProperties.class);
    if (gcpProperties.getProjectId() != null) {
      return gcpProperties::getProjectId;
    }
    return new DefaultGcpProjectIdProvider();
  }

  @VisibleForTesting
  static synchronized SecretManagerServiceClient createSecretManagerClient(
      ConfigDataLocationResolverContext context) {
    if (secretManagerServiceClient != null && !secretManagerServiceClient.isTerminated()) {
      return secretManagerServiceClient;
    }

    try {
      GcpSecretManagerProperties properties = context.getBootstrapContext()
          .get(GcpSecretManagerProperties.class);
      DefaultCredentialsProvider credentialsProvider =
          new DefaultCredentialsProvider(properties);
      SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder()
          .setCredentialsProvider(credentialsProvider)
          .setHeaderProvider(
              new UserAgentHeaderProvider(SecretManagerConfigDataLoader.class))
          .build();
      secretManagerServiceClient = SecretManagerServiceClient.create(settings);

      return secretManagerServiceClient;
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to create the Secret Manager Client for ConfigData loading.", e);
    }
  }

  private static SecretManagerTemplate createSecretManagerTemplate(
      ConfigDataLocationResolverContext context) {
    SecretManagerServiceClient client = context.getBootstrapContext()
        .get(SecretManagerServiceClient.class);
    GcpProjectIdProvider projectIdProvider = context.getBootstrapContext()
        .get(GcpProjectIdProvider.class);
    GcpSecretManagerProperties properties = context.getBootstrapContext()
        .get(GcpSecretManagerProperties.class);

    return new SecretManagerTemplate(client, projectIdProvider)
        .setAllowDefaultSecretValue(properties.isAllowDefaultSecret());
  }

  /**
   * Registers a bean in the Bootstrap Registry.
   *
   * <p>The Bootstrap Registry is a temporary context which exists for creating
   * the ConfigData property sources.
   */
  private static <T> void registerBean(
      ConfigDataLocationResolverContext context, Class<T> type, T instance) {
    context.getBootstrapContext()
        .registerIfAbsent(type, BootstrapRegistry.InstanceSupplier.of(instance));
  }

  /**
   * Registers the bean in the Bootstrap Registry *and* promotes it to be in the standard
   * application context.
   */
  private static <T> void registerAndPromoteBean(
      ConfigDataLocationResolverContext context, Class<T> type,
      BootstrapRegistry.InstanceSupplier<T> supplier) {
    context.getBootstrapContext().registerIfAbsent(type, supplier);
    context.getBootstrapContext().addCloseListener(event -> {
      T instance = event.getBootstrapContext().get(type);
      String beanName = "gcp-secretmanager-config-data-" + type.getSimpleName();
      ConfigurableListableBeanFactory factory = event.getApplicationContext().getBeanFactory();
      if (!factory.containsSingleton(beanName)) {
        factory.registerSingleton(beanName, instance);
      }
    });
  }

  @VisibleForTesting
  static void setSecretManagerServiceClient(SecretManagerServiceClient client) {
    secretManagerServiceClient = client;
  }
}
