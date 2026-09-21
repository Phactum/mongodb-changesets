package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;

/**
 * What a step itself writes with, which is not always what the record about it was written with.
 * <p>
 * The library sets the promise of the migration on the MongoTemplate, and it hands that template
 * to the step. So the promise reaches every write the step makes through that template, and it
 * reaches nothing else. A collection the step takes out of the template is a collection of the
 * connection, and a client the step opens is a connection of its own. Both write with what their
 * connection says, and neither of them is touched by the migration.
 * <p>
 * The library cannot see this. It calls a method, and what the method does inside is its own
 * business. So these tests do not say what the library should do about it. They say where the
 * promise stops, so that a change which moves that line is noticed.
 * <p>
 * Where the promise stops matters, because the record about a step is written before the step
 * runs. A record written with more than the work of the step can survive a failover which the
 * work does not, and the next start skips a step which never happened.
 */
class WriteConcernOfWhatAStepWritesTest extends AgainstARealMongoDb {

  private static final String COLLECTION_OF_THE_STEP = "customer";

  /**
   * Every write the database was asked for while a test ran, by the collection it went to. Static,
   * because a step reaches it and a step is a method of a bean the application builds.
   */
  private static WhatEveryWriteAsked whatEveryWriteAsked;

  /**
   * How a step opens a connection of its own to the database of the running test.
   */
  private static String whereTheDatabaseIs;

  private static String nameOfTheDatabase;

  @BeforeEach
  void startWithNothingWrittenDown() {

    whatEveryWriteAsked = new WhatEveryWriteAsked();
    whereTheDatabaseIs = connectionString();
    nameOfTheDatabase = databaseName();

  }

  /**
   * The write concern of every write command, kept per collection.
   * <p>
   * This is the promise as MongoDB sees it. The driver leaves the field out where the write
   * concern is the one of the server, so a <code>null</code> in such a list says that the write
   * promised nothing of its own.
   */
  static class WhatEveryWriteAsked implements CommandListener {

    private final Map<String, List<BsonDocument>> byCollection = new LinkedHashMap<>();

    @Override
    public void commandStarted(
        final CommandStartedEvent event) {

      final var commandName = event.getCommandName();
      if (!commandName.equals("insert") && !commandName.equals("update")) {
        return;
      }

      final var command = event.getCommand();
      byCollection
          .computeIfAbsent(command.getString(commandName).getValue(), collection -> new ArrayList<>())
          .add(command.getDocument("writeConcern", null));

    }

    List<BsonDocument> writesTo(
        final String collection) {

      return byCollection.getOrDefault(collection, List.of());

    }

  }

  /**
   * A connection to the database of this test which tells {@link #whatEveryWriteAsked} about every
   * command it sends. The caller closes it.
   */
  private static MongoClient clientTellingTheTest() {

    return MongoClients.create(
        MongoClientSettings
            .builder()
            .applyConnectionString(new ConnectionString(whereTheDatabaseIs))
            .addCommandListener(whatEveryWriteAsked)
            .build());

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepWritingThroughTheTemplate {

    @DbChangeset(order = 10)
    public String insertsThroughTheTemplateItWasHanded(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.insert(new Document("_id", "one"), COLLECTION_OF_THE_STEP);
      return null;

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepWithACollectionOfItsOwn {

    @DbChangeset(order = 10)
    public String insertsThroughACollectionItTookOutOfTheTemplate(
        final MongoTemplate mongoTemplate) {

      mongoTemplate
          .getCollection(COLLECTION_OF_THE_STEP)
          .insertOne(new Document("_id", "one"));
      return null;

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepWithAClientOfItsOwn {

    @DbChangeset(order = 10)
    public String insertsThroughAConnectionItOpenedItself() {

      try (var client = clientTellingTheTest()) {

        client
            .getDatabase(nameOfTheDatabase)
            .getCollection(COLLECTION_OF_THE_STEP)
            .insertOne(new Document("_id", "one"));

      }
      return null;

    }

  }

  /**
   * An application which writes with <code>majority</code> and whose commands this test reads. The
   * caller closes the client.
   */
  private static MongoTemplate templateOfAnApplicationWritingWithMajority(
      final MongoClient client) {

    final var template = new MongoTemplate(
        new SimpleMongoClientDatabaseFactory(client, nameOfTheDatabase));
    template.setWriteConcern(WriteConcern.MAJORITY);
    return template;

  }

  private static BsonDocument whatTheMigrationPromised() {

    return WriteConcern.MAJORITY.withJournal(true).asDocument();

  }

  @Test
  void aStepWritingThroughTheTemplateWritesWithThePromiseOfTheMigration() {

    try (var client = clientTellingTheTest()) {

      applicationOf(
          templateOfAnApplicationWritingWithMajority(client),
          AStepWritingThroughTheTemplate.class)
          .run(context -> assertThat(context).hasNotFailed());

    }

    // the step and the record about it promise the same, because both went through the template
    assertThat(whatEveryWriteAsked.writesTo(ChangesetInformation.COLLECTION_NAME))
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern).isEqualTo(whatTheMigrationPromised()));
    assertThat(whatEveryWriteAsked.writesTo(COLLECTION_OF_THE_STEP))
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern).isEqualTo(whatTheMigrationPromised()));

  }

  @Test
  void aCollectionTheStepTookOutOfTheTemplateWritesWithoutThatPromise() {

    try (var client = clientTellingTheTest()) {

      applicationOf(
          templateOfAnApplicationWritingWithMajority(client),
          AStepWithACollectionOfItsOwn.class)
          .run(context -> assertThat(context).hasNotFailed());

    }

    assertThat(whatEveryWriteAsked.writesTo(ChangesetInformation.COLLECTION_NAME))
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern).isEqualTo(whatTheMigrationPromised()));
    // MongoTemplate.getCollection hands out the collection of the connection. The write concern of
    // the template never touches it, so this write promises nothing at all, while the record about
    // the step asked for a majority and the journal. The url of this test names no promise either,
    // which is why the field is missing rather than holding the value of the application.
    assertThat(whatEveryWriteAsked.writesTo(COLLECTION_OF_THE_STEP))
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern).isNull());

  }

  @Test
  void aClientTheStepOpenedItselfWritesWithoutThatPromiseEither() {

    try (var client = clientTellingTheTest()) {

      applicationOf(
          templateOfAnApplicationWritingWithMajority(client),
          AStepWithAClientOfItsOwn.class)
          .run(context -> assertThat(context).hasNotFailed());

    }

    assertThat(whatEveryWriteAsked.writesTo(ChangesetInformation.COLLECTION_NAME))
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern).isEqualTo(whatTheMigrationPromised()));
    // a connection the step opened is one the library never saw, so there is nothing it could have
    // done here
    assertThat(whatEveryWriteAsked.writesTo(COLLECTION_OF_THE_STEP))
        .isNotEmpty()
        .allSatisfy(writeConcern -> assertThat(writeConcern).isNull());

  }

}
