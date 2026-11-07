package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The order of a step is its identity as well as its position, see decision 8 in the repository's
 * DECISIONS.md. A number is taken once and for good. Gaps are fine, and a new step may take a free
 * number below the ones a database applied already.
 * <p>
 * A start which finds a number taken twice ends before any step runs. The tests check that nothing
 * was applied, because a step which ran next to the conflict would be the damage the check is
 * there to prevent.
 */
class AnOrderIsTheIdentityOfAStepTest extends AgainstARealMongoDb {

  @DbChangesetConfiguration(author = "the team")
  static class TheOldSoftware {

    @DbChangeset(order = 10)
    public String createTheLetters(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("letters");
      return "{ drop: 'letters' }";

    }

    @DbChangeset(order = 20)
    public String createTheNumbers(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("numbers");
      return "{ drop: 'numbers' }";

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepInAFreeGapBelowTheAppliedOnes {

    @DbChangeset(order = 15)
    public String createTheWords(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("words");
      return "{ drop: 'words' }";

    }

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

  /**
   * The step of {@link TheOldSoftware} with order 10, renamed. For this library it is another
   * step, because its id is another one.
   */
  @DbChangesetConfiguration(author = "the team")
  static class TheRenamedLetters {

    @DbChangeset(order = 10)
    public String createTheLetters(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("letters");
      return "{ drop: 'letters' }";

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepWhichComesLater {

    @DbChangeset(order = 30)
    public String createTheSentences(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("sentences");
      return "{ drop: 'sentences' }";

    }

  }

  private static final String LETTERS = TheOldSoftware.class.getName()
      + "#createTheLetters";

  private static final String NUMBERS = TheOldSoftware.class.getName()
      + "#createTheNumbers";

  private static final String RENAMED_LETTERS = TheRenamedLetters.class.getName()
      + "#createTheLetters";

  @Test
  void aNewStepMayTakeAFreeOrderBelowTheAppliedOnes() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());

    applicationHaving(TheOldSoftware.class, AStepInAFreeGapBelowTheAppliedOnes.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(mongoTemplate().getCollectionNames()).contains("letters", "numbers", "words");
    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).hasSize(3);

  }

  @Test
  void twoStepsWithTheSameOrderEndTheStart() {

    applicationHaving(TwoStepsClaimingTheSameOrder.class)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("have the same order 7")
            .hasMessageContaining(TwoStepsClaimingTheSameOrder.class.getName()
                + "#one")
            .hasMessageContaining(TwoStepsClaimingTheSameOrder.class.getName()
                + "#other")
            .hasMessageContaining("Give one of them an order which no other changeset uses"));

    // the start is refused while the steps are collected, so nothing was written down
    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).isEmpty();

  }

  @Test
  void anOrderTheDatabaseHasForAnotherStepEndsTheStart() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());
    mongoTemplate().dropCollection("letters");

    applicationHaving(TheRenamedLetters.class, AStepWhichComesLater.class)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("'"
                + RENAMED_LETTERS
                + "' has order 10")
            .hasMessageContaining("applied changeset '"
                + LETTERS
                + "' already has that order"));

    // neither the renamed step nor the one after it ran
    assertThat(mongoTemplate().getCollectionNames()).doesNotContain("letters", "sentences");
    assertThat(idsInTheDatabase()).containsExactlyInAnyOrder(LETTERS, NUMBERS);

  }

  @Test
  void aStepWhoseOrderDiffersFromItsRecordEndsTheStart() {

    // the record says the step ran with order 99. Today's code gives it order 10.
    mongoTemplate()
        .getCollection(ChangesetInformation.COLLECTION_NAME)
        .insertOne(new Document("_id", LETTERS)
            .append("version", 1L)
            .append("author", "the team")
            .append("order", 99)
            .append("rollbackScripts", List.of("{ drop: 'letters' }")));

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("'"
                + LETTERS
                + "' has order 10")
            .hasMessageContaining("the database applied it with order 99"));

    // the step did not run a second time, and the step after it did not run either
    assertThat(mongoTemplate().getCollectionNames()).doesNotContain("letters", "numbers");
    assertThat(idsInTheDatabase()).containsExactly(LETTERS);

  }

  @Test
  void anUnknownRecordWithAFreeOrderIsNoConflict() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());
    // what a downgrade leaves behind: a step of a newer version, which this software does not
    // know. No step of this software uses its order.
    mongoTemplate()
        .getCollection(ChangesetInformation.COLLECTION_NAME)
        .insertOne(new Document("_id", "a.newer.version#aStep")
            .append("version", 1L)
            .append("author", "somebody")
            .append("order", 25));

    applicationHaving(TheOldSoftware.class, AStepWhichComesLater.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(mongoTemplate().getCollectionNames()).contains("sentences");
    assertThat(idsInTheDatabase()).contains("a.newer.version#aStep");

  }

  @Test
  void anUnknownRecordWhoseOrderIsTakenEndsTheStartAlsoWhenItIsToBeRolledBack() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());

    // the unknown step would be rolled back only after the new one ran, and its rollback could
    // undo what the new one did. So the property changes nothing.
    System.setProperty("initializer.rollback.unknown", "true");
    try {
      applicationHaving(TheRenamedLetters.class)
          .run(context -> assertThat(context)
              .hasFailed()
              .getFailure()
              .rootCause()
              .hasMessageContaining("applied changeset '"
                  + LETTERS
                  + "' already has that order"));
    } finally {
      System.clearProperty("initializer.rollback.unknown");
    }

    // nothing was rolled back and nothing ran
    assertThat(mongoTemplate().getCollectionNames()).contains("letters", "numbers");
    assertThat(idsInTheDatabase()).containsExactlyInAnyOrder(LETTERS, NUMBERS);

  }

  @Test
  void theRollbackOfEverythingStillWorksWhenAnOrderIsTaken() throws Exception {

    applicationHaving(RollbackAllNode.ChangesetsOfThatNode.class)
        .run(context -> assertThat(context).hasNotFailed());
    // another step has the order of the step of that node
    mongoTemplate()
        .getCollection(ChangesetInformation.COLLECTION_NAME)
        .insertOne(new Document("_id", "written.by.hand#aStep")
            .append("version", 1L)
            .append("author", "somebody")
            .append("order", 10));

    final var node = RollbackAllNode.startAgainst(connectionString(), "true");
    assertThat(node.waitFor(2, TimeUnit.MINUTES))
        .withFailMessage("the node did not end on its own")
        .isTrue();

    // the rollback is the way out of a conflict, so the check does not stand in its way
    assertThat(node.exitValue()).isEqualTo(1);
    assertThat(mongoTemplate().getCollectionNames()).doesNotContain("letters");
    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).isEmpty();

  }

  private List<String> idsInTheDatabase() {

    return mongoTemplate()
        .findAll(ChangesetInformation.class)
        .stream()
        .map(ChangesetInformation::getId)
        .toList();

  }

}
