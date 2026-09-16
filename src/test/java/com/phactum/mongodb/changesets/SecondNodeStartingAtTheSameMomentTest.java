package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Two nodes of a cluster which start at the same moment.
 * <p>
 * The applier saves the record of a step before it runs the step. A node which read the
 * collection before the other node saved that record still has the step on its list. Its own save
 * is an insert, because the record it holds is new to it, and the record is in the collection
 * already. MongoDB refuses the second one, and that ends the start of the slower node instead of
 * applying the step twice.
 * <p>
 * Two processes racing would meet this moment by luck, and most runs would miss it. So the two
 * nodes here are two application contexts with a database connection each, on one database, and
 * the moment is made instead of waited for: the slower node is held inside the query which reads
 * what the database knows, until the faster node has finished. What it holds afterwards is what a
 * real second node holds, reached on purpose rather than by chance.
 */
class SecondNodeStartingAtTheSameMomentTest extends AgainstARealMongoDb {

  /**
   * What the step recorded. One entry per run of the step body, which is what the test counts.
   */
  static final List<String> invocations = new ArrayList<>();

  @DbChangesetConfiguration(author = "the team")
  static class TheStepBothNodesWantToRun {

    @DbChangeset(order = 10)
    public String createTheLetters(
        final MongoTemplate mongoTemplate) {

      invocations.add("the step");
      mongoTemplate.createCollection("letters");
      return "{ drop: 'letters' }";

    }

  }

  /**
   * A connection which lets the other node run to its end while this one reads what the database
   * knows. The read itself answers with what it found before, so this node goes on with the
   * picture the other node has made stale.
   */
  static class HoldingTheReadUntilTheOtherNodeIsDone extends MongoTemplate {

    private final Runnable theOtherNode;

    private boolean theOtherNodeHasRun;

    HoldingTheReadUntilTheOtherNodeIsDone(
        final MongoDatabaseFactory databaseFactory,
        final Runnable theOtherNode) {

      super(databaseFactory);
      this.theOtherNode = theOtherNode;

    }

    @Override
    public <T> List<T> find(
        final Query query,
        final Class<T> entityClass) {

      final var known = super.find(query, entityClass);
      if (!theOtherNodeHasRun) {
        theOtherNodeHasRun = true;
        theOtherNode.run();
      }
      return known;

    }

  }

  @Test
  void theSlowerNodeDoesNotApplyTheStepASecondTime() throws Exception {

    invocations.clear();

    final var connectionOfTheFasterNode = new SimpleMongoClientDatabaseFactory(connectionString());
    try {
      final var theFasterNode = new Thread(
          () -> applicationOf(
              new MongoTemplate(connectionOfTheFasterNode),
              TheStepBothNodesWantToRun.class)
              .run(context -> assertThat(context).hasNotFailed()));

      final var connectionOfTheSlowerNode = new HoldingTheReadUntilTheOtherNodeIsDone(
          databaseFactory(), () -> runToItsEnd(theFasterNode));

      applicationOf(connectionOfTheSlowerNode, TheStepBothNodesWantToRun.class)
          .run(context -> assertThat(context)
              .hasFailed()
              .getFailure()
              .rootCause()
              .hasMessageContaining("duplicate key"));
    } finally {
      connectionOfTheFasterNode.destroy();
    }

    // the step ran once, on the faster node, and the record of it is there once
    assertThat(invocations).containsExactly("the step");
    assertThat(mongoTemplate().findAll(ChangesetInformation.class))
        .extracting(ChangesetInformation::getId)
        .containsExactly(TheStepBothNodesWantToRun.class.getName()
            + "#createTheLetters");

  }

  private static void runToItsEnd(
      final Thread node) {

    node.start();
    try {
      node.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("waiting for the other node was interrupted", e);
    }

  }

}
