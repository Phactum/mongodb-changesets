package com.phactum.mongodb.changesets;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The base of every test which needs a MongoDB.
 * <p>
 * What this library promises is what the database promises, so the tests talk to a real server.
 * One container serves the whole run. What has to be fresh for a test is the database and not the
 * server, so every test gets a database of its own and a step which ran in one test is unknown to
 * the next.
 * <p>
 * The MongoTemplate is built once per test and handed to every application start of that test.
 * That is how one test starts the same application twice against the same database.
 */
abstract class AgainstARealMongoDb {

  // started once for the whole run and stopped by the Testcontainers reaper when the JVM ends. A
  // '@Container' field would start and stop a server per test class, which buys nothing because
  // the tests never share a database anyway.
  private static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:7.0");

  static {

    MONGODB.start();

  }

  private SimpleMongoClientDatabaseFactory databaseFactory;

  private String connectionString;

  private MongoTemplate mongoTemplate;

  @BeforeEach
  void startWithAnEmptyDatabase() {

    connectionString = MONGODB.getConnectionString()
        + "/test"
        + UUID.randomUUID().toString().replace("-", "");
    databaseFactory = new SimpleMongoClientDatabaseFactory(connectionString);
    mongoTemplate = new MongoTemplate(databaseFactory);

  }

  @AfterEach
  void closeTheConnection() throws Exception {

    databaseFactory.destroy();

  }

  /**
   * The database of this test, as the template the application starts with.
   */
  protected MongoTemplate mongoTemplate() {

    return mongoTemplate;

  }

  /**
   * How another node reaches the database of this test.
   */
  protected String connectionString() {

    return connectionString;

  }

  /**
   * The connection behind {@link #mongoTemplate()}, for a test which builds a template of its
   * own on the same database.
   */
  protected SimpleMongoClientDatabaseFactory databaseFactory() {

    return databaseFactory;

  }

  /**
   * An application which has this library and the given changeset beans, and nothing else.
   */
  protected ApplicationContextRunner applicationHaving(
      final Class<?>... changesetBeans) {

    return applicationOf(mongoTemplate, changesetBeans);

  }

  /**
   * The same application, on a template the caller built. A test which plays two nodes needs
   * this, because each node has a template of its own.
   */
  protected static ApplicationContextRunner applicationOf(
      final MongoTemplate mongoTemplate,
      final Class<?>... changesetBeans) {

    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChangesetAutoConfiguration.class))
        .withBean(MongoTemplate.class, () -> mongoTemplate)
        .withUserConfiguration(changesetBeans);

  }

}
