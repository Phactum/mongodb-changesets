package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.phactum.mongodb.changesets.ChangesetProperties.MongoDbMode;

/**
 * The keys of this project sit under <code>mongodb.changesets</code>, which is below the prefix
 * <code>mongodb</code> an application may already be using. This test says what that costs.
 * <p>
 * A class bound with <code>ignoreUnknownFields = false</code> refuses every key under its prefix
 * which none of its fields takes. A key of a nested prefix is such a key. So an application which
 * binds <code>mongodb</code> strictly does not start once <code>mongodb.changesets.mode</code> is
 * set, and the fix belongs to that application, not here.
 */
class ChangesetPropertyPrefixTest {

  @ConfigurationProperties(prefix = "mongodb", ignoreUnknownFields = false)
  static class StrictMongoDbProperties {

    private boolean useTls;

    public boolean isUseTls() {
      return useTls;
    }

    public void setUseTls(
        final boolean useTls) {
      this.useTls = useTls;
    }

  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({
      StrictMongoDbProperties.class, ChangesetProperties.class
  })
  static class BothPrefixes {
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ChangesetProperties.class)
  static class OnlyTheChangesetPrefix {
  }

  @Test
  void theKeyIsReadWhenNobodyBindsTheOuterPrefixStrictly() {

    new ApplicationContextRunner()
        .withUserConfiguration(OnlyTheChangesetPrefix.class)
        .withPropertyValues("mongodb.changesets.mode=AZURE_COSMOS_MONGO_4_2")
        .run(context -> assertThat(context.getBean(ChangesetProperties.class).getMode())
            .isEqualTo(MongoDbMode.AZURE_COSMOS_MONGO_4_2));

  }

  @Test
  void aStrictBindingOfTheOuterPrefixEndsTheStart() {

    new ApplicationContextRunner()
        .withUserConfiguration(BothPrefixes.class)
        .withPropertyValues("mongodb.use-tls=true", "mongodb.changesets.mode=MONGODB_4_8")
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("mongodb.changesets.mode"));

  }

  @Test
  void aStrictBindingOfTheOuterPrefixIsHappyUntilTheKeyIsSet() {

    new ApplicationContextRunner()
        .withUserConfiguration(BothPrefixes.class)
        .withPropertyValues("mongodb.use-tls=true")
        .run(context -> assertThat(context).hasNotFailed());

  }

}
