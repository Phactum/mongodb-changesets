package com.phactum.mongodb.changesets;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.MongoClient;

/**
 * Wires the changeset mechanism into a Spring Boot application.
 * <p>
 * The beans it publishes are the configuration of the mechanism and the {@link ChangesetApplier},
 * which does the work while the application starts. The applier is created before the MongoDB
 * repositories are, so no repository is used on a database which has not been migrated yet.
 * <p>
 * An application which holds the MongoDB mode somewhere else already can publish a
 * {@link ChangesetProperties} bean of its own. Then the key <code>mongodb.changesets.mode</code>
 * is not needed, because the bean of the application is used instead of the one created here.
 *
 * @see DbChangeset
 * @see DbChangesetConfiguration
 */
// registered through the .imports file, so it has to be an @AutoConfiguration. Only then are
// @AutoConfigureBefore and @AutoConfigureAfter read
@AutoConfiguration
@AutoConfigureBefore(DataMongoRepositoriesAutoConfiguration.class)
@ConditionalOnClass({
    MongoClient.class, MongoTemplate.class
})
public class ChangesetAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ChangesetProperties mongoDbChangesetProperties() {

    return new ChangesetProperties();

  }

  @Bean
  public ChangesetApplier mongoDbChangesetApplier(
      final ApplicationContext applicationContext,
      final MongoTemplate mongoTemplate,
      final ChangesetProperties properties) {

    return new ChangesetApplier(applicationContext, mongoTemplate, properties);

  }

}
