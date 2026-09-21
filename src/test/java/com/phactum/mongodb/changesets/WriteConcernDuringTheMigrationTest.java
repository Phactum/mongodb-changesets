package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.WriteResultChecking;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

/**
 * What the migration promises while it runs, and what it does to the write concern of the
 * application.
 * <p>
 * A record of the migration says that a step ran. It has to survive a node which dies right
 * after writing it, so the migration adds <code>j: true</code> to what the application promises
 * and keeps the <code>w</code> of the application. Where the application promises nothing to
 * grow from, the migration writes with <code>w: 1, j: true</code>. The template it writes
 * through belongs to the application, so the promise of the migration lasts for the migration
 * only and the template is given back the way it was handed over.
 * <p>
 * Two things are tested here, and they are not the same. What the library sets on the template
 * is one, and a template which writes down every value it is given says that. What MongoDB is
 * handed is the other, and only a listener on the connection says that, because Spring Data may
 * still replace the value on its way to the driver.
 */
class WriteConcernDuringTheMigrationTest extends AgainstARealMongoDb {

  /**
   * What the library promises where the application gives it nothing to grow from. The primary
   * has the record and the record is in the journal of that primary, on disk.
   */
  static final WriteConcern whatTheMigrationPromisesWithoutOneToGrowFrom = WriteConcern.W1
      .withJournal(true);

  /**
   * The write concern each step found on the template it was handed, in the order the steps ran.
   */
  static final List<WriteConcern> whatTheStepsSaw = new ArrayList<>();

  /**
   * The name of a write concern mode this test teaches the replica set.
   */
  private static final String ONE_TAGGED_NODE = "oneTaggedNode";

  @BeforeEach
  void forgetWhatEarlierTestsSaw() {

    whatTheStepsSaw.clear();

  }

  /**
   * A template which remembers every write concern set on it, so a test can say what the library
   * did to it and in which order.
   */
  static class TemplateWritingDownItsWriteConcern extends MongoTemplate {

    private final List<WriteConcern> valuesSet = new ArrayList<>();

    TemplateWritingDownItsWriteConcern(
        final MongoDatabaseFactory databaseFactory) {

      super(databaseFactory);

    }

    @Override
    public void setWriteConcern(
        final WriteConcern writeConcern) {

      super.setWriteConcern(writeConcern);
      valuesSet.add(writeConcern);

    }

    List<WriteConcern> valuesSet() {

      return valuesSet;

    }

    WriteConcern currentValue() {

      return valuesSet.isEmpty() ? null : valuesSet.get(valuesSet.size() - 1);

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepLookingAtTheWriteConcern {

    @DbChangeset(order = 10)
    public String readsTheWriteConcernItWasHanded(
        final MongoTemplate mongoTemplate) {

      whatTheStepsSaw.add(((TemplateWritingDownItsWriteConcern) mongoTemplate).currentValue());
      return null;

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepWhichBreaks {

    @DbChangeset(order = 10)
    public String createsNothing(
        final MongoTemplate mongoTemplate) {

      whatTheStepsSaw.add(((TemplateWritingDownItsWriteConcern) mongoTemplate).currentValue());
      throw new IllegalStateException("the database said no");

    }

  }

  @Test
  void theMigrationAddsTheJournalToThePromiseOfTheApplicationAndGivesTheTemplateBack() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());
    // this is what the application asked for, long before the migration
    template.setWriteConcern(WriteConcern.W1);

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    final var whatTheMigrationPromised = WriteConcern.W1.withJournal(true);
    assertThat(whatTheStepsSaw).containsExactly(whatTheMigrationPromised);
    assertThat(template.currentValue()).isEqualTo(WriteConcern.W1);
    assertThat(template.valuesSet())
        .containsExactly(WriteConcern.W1, whatTheMigrationPromised, WriteConcern.W1);

  }

  @Test
  void aStepWhichThrowsGivesTheTemplateBackToo() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());
    template.setWriteConcern(WriteConcern.W1);

    applicationOf(template, AStepWhichBreaks.class)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessage("the database said no"));

    assertThat(whatTheStepsSaw).containsExactly(WriteConcern.W1.withJournal(true));
    assertThat(template.currentValue()).isEqualTo(WriteConcern.W1);

  }

  @Test
  void anApplicationWhichSetNoWriteConcernHasNoneAfterwards() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    // neither the template nor the connection of this test names a 'w', so the migration has
    // nothing to grow from and writes with its own promise. Afterwards the template carries no
    // value again and the connection decides, which is how the application had it.
    assertThat(template.valuesSet())
        .containsExactly(whatTheMigrationPromisesWithoutOneToGrowFrom, null);

  }

  @Test
  void anApplicationWritingWithMajorityMigratesWithMajorityAndTheJournal() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());
    template.setWriteConcern(WriteConcern.MAJORITY);

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    // a library which writes weaker than the application it runs in would be a surprise, so the
    // 'majority' stays and only the journal is added
    assertThat(whatTheStepsSaw).containsExactly(WriteConcern.MAJORITY.withJournal(true));
    assertThat(template.valuesSet())
        .containsExactly(
            WriteConcern.MAJORITY,
            WriteConcern.MAJORITY.withJournal(true),
            WriteConcern.MAJORITY);

  }

  @Test
  void aPromiseWhichOnlyTheConnectionCarriesIsGrownFromTooAndTheTemplateStaysEmpty() throws Exception {

    // the application names its promise in the URL and sets nothing on the template. The field
    // of the template is empty in this case, while every write still asks for a majority.
    final var databaseFactory = new SimpleMongoClientDatabaseFactory(
        connectionString()
            + "?w=majority");
    try {

      final var template = new TemplateWritingDownItsWriteConcern(databaseFactory);

      applicationOf(template, AStepLookingAtTheWriteConcern.class)
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(whatTheStepsSaw).containsExactly(WriteConcern.MAJORITY.withJournal(true));
      // the template had no value of its own, so it has none afterwards either
      assertThat(template.valuesSet())
          .containsExactly(WriteConcern.MAJORITY.withJournal(true), null);

    } finally {
      databaseFactory.destroy();
    }

  }

  @Test
  void anApplicationWhichWritesUnacknowledgedMigratesWithWOneAndTheJournal() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());
    // 'w: 0' is a write nobody answers. A record written that way tells the next step nothing,
    // and the driver refuses the journal next to it, so the migration writes its own promise.
    template.setWriteConcern(WriteConcern.UNACKNOWLEDGED);

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(whatTheStepsSaw).containsExactly(whatTheMigrationPromisesWithoutOneToGrowFrom);
    assertThat(template.valuesSet())
        .containsExactly(
            WriteConcern.UNACKNOWLEDGED,
            whatTheMigrationPromisesWithoutOneToGrowFrom,
            WriteConcern.UNACKNOWLEDGED);

  }

  @Test
  void aNumberOfTheApplicationIsKeptEvenWhereTheClusterCannotAnswerIt() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());
    // one server answers this test, so a 'w: 2' cannot be met here. The migration keeps the
    // number all the same, because lowering it would write weaker than the application asked
    // for, and the start ends with the error of the database.
    template.setWriteConcern(WriteConcern.W2);

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasFailed());

    assertThat(whatTheStepsSaw).isEmpty();
    assertThat(template.valuesSet())
        .containsExactly(
            WriteConcern.W2,
            WriteConcern.W2.withJournal(true),
            WriteConcern.W2);

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepDoingNothing {

    @DbChangeset(order = 10)
    public String changesNothing() {

      return null;

    }

  }

  /**
   * The write concern of every command which wrote a record of the migration, in the order the
   * driver sent those commands.
   * <p>
   * This is the promise as MongoDB sees it, and it is not the same thing as the value the library
   * sets on the template. Spring Data may replace that value on its way to the driver, and a
   * command which names no write concern at all is what a replaced one looks like from here.
   */
  static class WhatTheDatabaseWasHanded implements CommandListener {

    private final List<BsonDocument> writeConcerns = new ArrayList<>();

    @Override
    public void commandStarted(
        final CommandStartedEvent event) {

      final var commandName = event.getCommandName();
      if (!commandName.equals("insert") && !commandName.equals("update")) {
        return;
      }
      final var command = event.getCommand();
      if (!command.getString(commandName).getValue()
          .equals(ChangesetInformation.COLLECTION_NAME)) {
        return;
      }

      // the driver leaves the field out where the write concern is the one of the server, so a
      // null here says that nothing was promised
      writeConcerns.add(command.getDocument("writeConcern", null));

    }

    List<BsonDocument> writeConcerns() {

      return writeConcerns;

    }

  }

  /**
   * A connection to the database of this test which tells the given listener about every command
   * it sends. The caller closes it.
   */
  private MongoClient clientTelling(
      final CommandListener listener) {

    return MongoClients.create(
        MongoClientSettings
            .builder()
            .applyConnectionString(new ConnectionString(connectionString()))
            .addCommandListener(listener)
            .build());

  }

  @Test
  void thePromiseReachesTheDatabase() {

    final var whatTheDatabaseWasHanded = new WhatTheDatabaseWasHanded();
    try (var client = clientTelling(whatTheDatabaseWasHanded)) {

      final var template = new MongoTemplate(
          new SimpleMongoClientDatabaseFactory(client, databaseName()));

      applicationOf(template, AStepDoingNothing.class)
          .run(context -> assertThat(context).hasNotFailed());

    }

    assertThat(whatTheDatabaseWasHanded.writeConcerns())
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern)
            .isEqualTo(whatTheMigrationPromisesWithoutOneToGrowFrom.asDocument()));

  }

  @Test
  void thePromiseAlsoReachesTheDatabaseOfAnApplicationWhichChecksItsWriteResults() {

    final var whatTheDatabaseWasHanded = new WhatTheDatabaseWasHanded();
    try (var client = clientTelling(whatTheDatabaseWasHanded)) {

      final var template = new MongoTemplate(
          new SimpleMongoClientDatabaseFactory(client, databaseName()));
      // an application which wants an exception when a write did not do what it asked for. Spring
      // Data answers that wish by replacing every write concern which names no 'w', and a promise
      // without a 'w' never reaches the database in such an application.
      template.setWriteResultChecking(WriteResultChecking.EXCEPTION);

      applicationOf(template, AStepDoingNothing.class)
          .run(context -> assertThat(context).hasNotFailed());

    }

    assertThat(whatTheDatabaseWasHanded.writeConcerns())
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern)
            .isEqualTo(whatTheMigrationPromisesWithoutOneToGrowFrom.asDocument()));

  }

  @Test
  void theMajorityOfTheApplicationReachesTheDatabaseAlongWithTheJournal() {

    final var whatTheDatabaseWasHanded = new WhatTheDatabaseWasHanded();
    try (var client = clientTelling(whatTheDatabaseWasHanded)) {

      final var template = new MongoTemplate(
          new SimpleMongoClientDatabaseFactory(client, databaseName()));
      template.setWriteConcern(WriteConcern.MAJORITY);
      // Spring Data replaces a write concern which names no 'w' or a 'w' below one. A word like
      // 'majority' is none of the two, so the grown promise survives the way to the driver even
      // in an application which checks its write results.
      template.setWriteResultChecking(WriteResultChecking.EXCEPTION);

      applicationOf(template, AStepDoingNothing.class)
          .run(context -> assertThat(context).hasNotFailed());

    }

    assertThat(whatTheDatabaseWasHanded.writeConcerns())
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern)
            .isEqualTo(WriteConcern.MAJORITY.withJournal(true).asDocument()));

  }

  /**
   * Teaches the replica set a write concern mode, so a test can write with a name of its own
   * instead of a number or the word <code>majority</code>.
   * <p>
   * A mode names how many nodes of which tag have to answer a write. The set of this test has one
   * member, so the member gets a tag and the mode asks for one node carrying it. That is a
   * promise the set can keep, and it is the kind of promise which says more than a majority does,
   * because a majority may sit anywhere while a tag says where.
   * <p>
   * The set keeps the mode for the rest of the run. That changes nothing for the other tests,
   * because a mode is only a name they never use.
   */
  private void teachTheSetAWriteConcernMode() {

    try (var client = MongoClients.create(connectionString())) {

      final var admin = client.getDatabase("admin");
      final var configuration = admin
          .runCommand(new Document("replSetGetConfig", 1))
          .get("config", Document.class);
      configuration
          .getList("members", Document.class)
          .get(0)
          .put("tags", new Document("dc", "the only one"));
      configuration
          .get("settings", Document.class)
          .put("getLastErrorModes", new Document(ONE_TAGGED_NODE, new Document("dc", 1)));
      // a new configuration is only taken where it counts up from the one the set has
      configuration.put("version", configuration.getInteger("version") + 1);
      admin.runCommand(new Document("replSetReconfig", configuration));

    }

  }

  @Test
  void aWriteConcernModeOfTheApplicationReachesTheDatabaseAlongWithTheJournal() {

    teachTheSetAWriteConcernMode();
    final var whatTheApplicationPromises = new WriteConcern(ONE_TAGGED_NODE);
    final var whatTheMigrationPromises = whatTheApplicationPromises.withJournal(true);

    final var whatTheDatabaseWasHanded = new WhatTheDatabaseWasHanded();
    try (var client = clientTelling(whatTheDatabaseWasHanded)) {

      final var template = new TemplateWritingDownItsWriteConcern(
          new SimpleMongoClientDatabaseFactory(client, databaseName()));
      // a name is neither an empty 'w' nor a 'w' below one, so Spring Data leaves it alone even
      // in an application which checks its write results
      template.setWriteConcern(whatTheApplicationPromises);
      template.setWriteResultChecking(WriteResultChecking.EXCEPTION);

      applicationOf(template, AStepLookingAtTheWriteConcern.class)
          .run(context -> assertThat(context).hasNotFailed());

      assertThat(whatTheStepsSaw).containsExactly(whatTheMigrationPromises);
      assertThat(template.valuesSet())
          .containsExactly(
              whatTheApplicationPromises,
              whatTheMigrationPromises,
              whatTheApplicationPromises);

    }

    // the writes went through, so the set answered the mode. A server without replication would
    // not even know the name.
    assertThat(whatTheDatabaseWasHanded.writeConcerns())
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern)
            .isEqualTo(whatTheMigrationPromises.asDocument()));

  }

}
