package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * What the two rollback system properties do.
 * <p>
 * <code>initializer.rollback.unknown</code> rolls back the steps the database knows and this
 * build of the software does not, which is what a downgrade leaves behind, and the application
 * comes up afterwards. <code>initializer.rollback.all</code> rolls every known step back and ends
 * the process.
 * <p>
 * Both are read with {@link System#getProperty(String)}, so a test sets the property and takes it
 * back again. The tests of this project run one after the other, so no other test sees it while
 * it is set.
 */
class ChangesetRollbackTest extends AgainstARealMongoDb {

  private static final String ROLLBACK_UNKNOWN = "initializer.rollback.unknown";

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
  static class TheNewSoftware {

    @DbChangeset(order = 30)
    public String createTheWords(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("words");
      return "{ drop: 'words' }";

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class TheOldSoftwareWithOneBrokenScript {

    @DbChangeset(order = 10)
    public String createTheLetters(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("letters");
      return "{ drop: 'letters' }";

    }

    @DbChangeset(order = 20)
    public String answersWithSomethingMongoDbDoesNotKnow() {

      return "{ notACommand: 1 }";

    }

  }

  @Test
  void whatThisSoftwareDoesNotKnowIsRolledBackAndTheApplicationComesUp() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(mongoTemplate().getCollectionNames()).contains("letters", "numbers");

    withTheProperty(ROLLBACK_UNKNOWN, () -> applicationHaving(TheNewSoftware.class)
        .run(context -> assertThat(context).hasNotFailed()));

    // the two steps of the old software are gone, the step of the new one ran
    assertThat(mongoTemplate().getCollectionNames())
        .doesNotContain("letters", "numbers")
        .contains("words");
    assertThat(mongoTemplate().findAll(ChangesetInformation.class))
        .extracting(ChangesetInformation::getId)
        .containsExactly(TheNewSoftware.class.getName()
            + "#createTheWords");

  }

  @Test
  void withoutTheSystemPropertyTheUnknownStepsStay() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());

    applicationHaving(TheNewSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(mongoTemplate().getCollectionNames()).contains("letters", "numbers", "words");
    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).hasSize(3);

  }

  @Test
  void aScriptWhichFailsDoesNotStopTheRollback() {

    applicationHaving(TheOldSoftwareWithOneBrokenScript.class)
        .run(context -> assertThat(context).hasNotFailed());

    withTheProperty(ROLLBACK_UNKNOWN, () -> applicationHaving(TheNewSoftware.class)
        .run(context -> assertThat(context).hasNotFailed()));

    // the rollback runs the steps newest first, so the broken script comes before the one which
    // works. It failed and the rollback went on, which is why 'letters' is gone.
    assertThat(mongoTemplate().getCollectionNames()).doesNotContain("letters");
    // a step whose script failed keeps its record, so the database still says the step is there
    assertThat(mongoTemplate().findAll(ChangesetInformation.class))
        .extracting(ChangesetInformation::getId)
        .containsExactlyInAnyOrder(
            TheOldSoftwareWithOneBrokenScript.class.getName()
                + "#answersWithSomethingMongoDbDoesNotKnow",
            TheNewSoftware.class.getName()
                + "#createTheWords");

  }

  @Test
  void theRollbackOfEverythingUndoesEveryStepAndEndsTheProcess() throws Exception {

    applicationHaving(RollbackAllNode.ChangesetsOfThatNode.class)
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(mongoTemplate().getCollectionNames()).contains("letters");

    final var node = RollbackAllNode.startAgainst(connectionString(), "true");
    final var ended = node.waitFor(2, TimeUnit.MINUTES);
    assertThat(ended)
        .withFailMessage("the node did not end on its own")
        .isTrue();

    // the process ends with the exit code the applier passes to System.exit
    assertThat(node.exitValue()).isEqualTo(1);
    assertThat(mongoTemplate().getCollectionNames()).doesNotContain("letters");
    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).isEmpty();

  }

  @Test
  void theValueIsReadTheWayAnOperatorTypesIt() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());

    // an operator types this on the day something is wrong. Upper case and a stray blank are
    // what was meant, so they count.
    withTheProperty(ROLLBACK_UNKNOWN, " TRUE ", () -> applicationHaving(TheNewSoftware.class)
        .run(context -> assertThat(context).hasNotFailed()));

    assertThat(mongoTemplate().getCollectionNames()).doesNotContain("letters", "numbers");

  }

  @Test
  void theRollbackOfEverythingReadsItsValueTheSameWay() throws Exception {

    applicationHaving(RollbackAllNode.ChangesetsOfThatNode.class)
        .run(context -> assertThat(context).hasNotFailed());

    // the two properties answer to the same spelling, so this one takes upper case as well
    final var node = RollbackAllNode.startAgainst(connectionString(), "TRUE");
    assertThat(node.waitFor(2, TimeUnit.MINUTES))
        .withFailMessage("the node did not end on its own")
        .isTrue();

    assertThat(node.exitValue()).isEqualTo(1);
    assertThat(mongoTemplate().getCollectionNames()).doesNotContain("letters");

  }

  private static void withTheProperty(
      final String name,
      final Runnable whileItIsSet) {

    withTheProperty(name, Boolean.TRUE.toString(), whileItIsSet);

  }

  private static void withTheProperty(
      final String name,
      final String value,
      final Runnable whileItIsSet) {

    System.setProperty(name, value);
    try {
      whileItIsSet.run();
    } finally {
      System.clearProperty(name);
    }

  }

}
