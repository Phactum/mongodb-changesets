package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonDocument;
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
 * after writing it, so the migration asks for <code>w: 1, j: true</code>. The template it writes
 * through belongs to the application, so the stricter promise lasts for the migration only and
 * the value the application had is put back afterwards.
 * <p>
 * Two things are tested here, and they are not the same. What the library sets on the template
 * is one, and a template which writes down every value it is given says that. What MongoDB is
 * handed is the other, and only a listener on the connection says that, because Spring Data may
 * still replace the value on its way to the driver.
 */
class WriteConcernDuringTheMigrationTest extends AgainstARealMongoDb {

  /**
   * What the library promises for the time of the migration. The primary has the record and the
   * record is in the journal of that primary, on disk.
   */
  static final WriteConcern whatTheMigrationPromises = WriteConcern.W1.withJournal(true);

  /**
   * The write concern each step found on the template it was handed, in the order the steps ran.
   */
  static final List<WriteConcern> whatTheStepsSaw = new ArrayList<>();

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
  void theMigrationWritesWithItsOwnPromiseAndGivesTheTemplateBackAsItWas() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());
    // this is what the application asked for, long before the migration
    template.setWriteConcern(WriteConcern.W1);

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(whatTheStepsSaw).containsExactly(whatTheMigrationPromises);
    assertThat(template.currentValue()).isEqualTo(WriteConcern.W1);
    assertThat(template.valuesSet())
        .containsExactly(WriteConcern.W1, whatTheMigrationPromises, WriteConcern.W1);

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

    assertThat(whatTheStepsSaw).containsExactly(whatTheMigrationPromises);
    assertThat(template.currentValue()).isEqualTo(WriteConcern.W1);

  }

  @Test
  void anApplicationWhichSetNoWriteConcernHasNoneAfterwards() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    // the application left the write concern of its template unset, so the connection decides
    // again once the migration is done
    assertThat(template.valuesSet()).containsExactly(whatTheMigrationPromises, null);

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
            .isEqualTo(whatTheMigrationPromises.asDocument()));

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
            .isEqualTo(whatTheMigrationPromises.asDocument()));

  }

}
