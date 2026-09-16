package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.phactum.mongodb.changesets.ChangesetProperties.MongoDbMode;

/**
 * What the mechanism promises, told against a real MongoDB.
 * <p>
 * Every test gets a database of its own, so a step which ran in one test is unknown to the next.
 * The MongoTemplate is built once per test and handed to every application start of that test,
 * which is how a test starts the same application twice against the same database.
 */
@Testcontainers
class ChangesetApplierTest {

  @Container
  private static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:7.0");

  /**
   * What the changeset methods of this test class recorded, in the order they ran.
   */
  static final List<String> invocations = new ArrayList<>();

  private SimpleMongoClientDatabaseFactory databaseFactory;

  private MongoTemplate mongoTemplate;

  @BeforeEach
  void startWithAnEmptyDatabase() {

    invocations.clear();
    databaseFactory = new SimpleMongoClientDatabaseFactory(
        MONGODB.getConnectionString()
            + "/test"
            + UUID.randomUUID().toString().replace("-", ""));
    mongoTemplate = new MongoTemplate(databaseFactory);

  }

  @AfterEach
  void closeTheConnection() throws Exception {

    databaseFactory.destroy();

  }

  private ApplicationContextRunner applicationHaving(
      final Class<?>... changesetBeans) {

    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChangesetAutoConfiguration.class))
        .withBean(MongoTemplate.class, () -> mongoTemplate)
        .withUserConfiguration(changesetBeans);

  }

  @DbChangesetConfiguration(author = "the team")
  static class TwoStepsOutOfOrder {

    @DbChangeset(order = 20)
    public String second(
        final MongoTemplate mongoTemplate) {

      invocations.add("second");
      mongoTemplate.getCollection("letters").insertOne(new org.bson.Document("value", "b"));
      return "{ drop: 'letters' }";

    }

    @DbChangeset(order = 10, author = "somebody else")
    public String first() {

      invocations.add("first");
      return null;

    }

  }

  @Test
  void aStepRunsOnceAndNotAgainOnTheNextStart() {

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(invocations).containsExactly("first", "second");

    invocations.clear();

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(invocations).isEmpty();

  }

  @Test
  void theOrderDecidesAndNotThePositionInTheClass() {

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(invocations).containsExactly("first", "second");

  }

  @Test
  void whatRanIsWrittenDown() {

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());

    final var records = mongoTemplate.findAll(ChangesetInformation.class);
    assertThat(records)
        .extracting(ChangesetInformation::getId)
        .containsExactlyInAnyOrder(
            TwoStepsOutOfOrder.class.getName()
                + "#first",
            TwoStepsOutOfOrder.class.getName()
                + "#second");

    final var first = records
        .stream()
        .filter(record -> record.getId().endsWith("#first"))
        .findFirst()
        .orElseThrow();
    assertThat(first.getOrder()).isEqualTo(10);
    // the method names an author of its own, so the author of the bean is not used
    assertThat(first.getAuthor()).isEqualTo("somebody else");
    assertThat(first.getTimestamp()).isNotNull();

    final var second = records
        .stream()
        .filter(record -> record.getId().endsWith("#second"))
        .findFirst()
        .orElseThrow();
    assertThat(second.getOrder()).isEqualTo(20);
    // the method names no author, so it takes the one of its bean
    assertThat(second.getAuthor()).isEqualTo("the team");

  }

  @Test
  void aRollbackScriptIsStoredWithTheStepThatReturnedIt() {

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());

    final var records = mongoTemplate.findAll(ChangesetInformation.class);
    final var second = records
        .stream()
        .filter(record -> record.getId().endsWith("#second"))
        .findFirst()
        .orElseThrow();
    assertThat(second.getRollbackScripts()).containsExactly("{ drop: 'letters' }");

    // a step with nothing to undo answers with null, and then no script is stored
    final var first = records
        .stream()
        .filter(record -> record.getId().endsWith("#first"))
        .findFirst()
        .orElseThrow();
    assertThat(first.getRollbackScripts()).isEmpty();

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepAnsweringWithSeveralScripts {

    @DbChangeset(order = 1)
    public List<String> twoThingsToUndo() {

      return List.of("{ drop: 'one' }", "{ drop: 'two' }");

    }

  }

  @Test
  void aStepMayAnswerWithSeveralRollbackScripts() {

    applicationHaving(AStepAnsweringWithSeveralScripts.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(mongoTemplate.findAll(ChangesetInformation.class))
        .singleElement()
        .extracting(ChangesetInformation::getRollbackScripts)
        .isEqualTo(List.of("{ drop: 'one' }", "{ drop: 'two' }"));

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepAnsweringWithANumber {

    @DbChangeset(order = 1)
    public int howManyDocumentsWereTouched() {

      invocations.add("should never run");
      return 3;

    }

  }

  @Test
  void aReturnTypeWhichIsNeitherStringNorCollectionEndsTheStart() {

    applicationHaving(AStepAnsweringWithANumber.class)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("has to be either String, String[] or Collection<String>")
            .hasMessageContaining("#howManyDocumentsWereTouched"));

    assertThat(invocations).isEmpty();
    // the start is refused while the steps are collected, so nothing was written down
    assertThat(mongoTemplate.findAll(ChangesetInformation.class)).isEmpty();

  }

  @DbChangesetConfiguration(author = "the team")
  static class TwoStepsClaimingTheSameOrder {

    @DbChangeset(order = 7)
    public String one() {

      return null;

    }

    @DbChangeset(order = 7)
    public String other() {

      return null;

    }

  }

  @Test
  void twoStepsWithTheSameOrderEndTheStart() {

    applicationHaving(TwoStepsClaimingTheSameOrder.class)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("Got at least two changesets having the same order value '7'"));

  }

  @Test
  void theModeIsReadFromTheConfiguration() {

    applicationHaving(AStepAnsweringWithSeveralScripts.class)
        .withPropertyValues(ChangesetProperties.PREFIX
            + ".mode=AZURE_COSMOS_MONGO_4_2")
        .run(context -> assertThat(context.getBean(ChangesetProperties.class).getMode())
            .isEqualTo(MongoDbMode.AZURE_COSMOS_MONGO_4_2));

    // Azure Cosmos answers a sorted query with an error unless the field is indexed, so the
    // collection gets that index while it is created
    assertThat(mongoTemplate.indexOps(ChangesetInformation.COLLECTION_NAME).getIndexInfo())
        .extracting(index -> index.getName())
        .contains(ChangesetInformation.COLLECTION_NAME
            + "_order");

  }

  @Test
  void theDefaultModeIsAPlainMongoDb() {

    applicationHaving(AStepAnsweringWithSeveralScripts.class)
        .run(context -> assertThat(context.getBean(ChangesetProperties.class).getMode())
            .isEqualTo(MongoDbMode.MONGODB_4_8));

    assertThat(mongoTemplate.indexOps(ChangesetInformation.COLLECTION_NAME).getIndexInfo())
        .extracting(index -> index.getName())
        .doesNotContain(ChangesetInformation.COLLECTION_NAME
            + "_order");

  }

  @Test
  void anApplicationMayBringItsOwnConfigurationBean() {

    final var ownProperties = new ChangesetProperties();
    ownProperties.setMode(MongoDbMode.AZURE_COSMOS_MONGO_4_2);

    applicationHaving(AStepAnsweringWithSeveralScripts.class)
        .withBean(ChangesetProperties.class, () -> ownProperties)
        .run(context -> {
          assertThat(context).hasSingleBean(ChangesetProperties.class);
          assertThat(context.getBean(ChangesetProperties.class)).isSameAs(ownProperties);
        });

    assertThat(mongoTemplate.indexOps(ChangesetInformation.COLLECTION_NAME).getIndexInfo())
        .extracting(index -> index.getName())
        .contains(ChangesetInformation.COLLECTION_NAME
            + "_order");

  }

}
