package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * What the mechanism promises about collecting the steps, ordering them, running them and writing
 * down what ran.
 */
class ChangesetApplierTest extends AgainstARealMongoDb {

  /**
   * What the changeset methods of this test class recorded, in the order they ran.
   */
  static final List<String> invocations = new ArrayList<>();

  /**
   * The time each step found in the record written about it, before the step body ran.
   */
  static final List<Instant> timestampsSeenWhileTheStepsRan = new ArrayList<>();

  @BeforeEach
  void forgetWhatEarlierTestsRecorded() {

    invocations.clear();
    timestampsSeenWhileTheStepsRan.clear();

  }

  @DbChangesetConfiguration(author = "the team")
  static class TwoStepsOutOfOrder {

    @DbChangeset(order = 20)
    public String second(
        final MongoTemplate mongoTemplate) {

      invocations.add("second");
      mongoTemplate.getCollection("letters").insertOne(new Document("value", "b"));
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

    // 'second' stands above 'first' in the class, and reflection answers with the methods of a
    // class in no defined order at all. Only the order of the annotation says what runs first.
    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(invocations).containsExactly("first", "second");

  }

  @DbChangesetConfiguration(author = "the team")
  static class TheBeanBuiltFirst {

    @DbChangeset(order = 30)
    public String theLateStep() {

      invocations.add("the late step");
      return null;

    }

  }

  @DbChangesetConfiguration(author = "the team")
  static class TheBeanBuiltSecond {

    @DbChangeset(order = 20)
    public String theEarlyStep() {

      invocations.add("the early step");
      return null;

    }

  }

  @Test
  void theOrderDecidesAndNotTheOrderTheBeansAreBuiltIn() {

    // the bean registered first holds the step which has to run last, so a mechanism which
    // simply walked the beans would get this the wrong way round
    applicationHaving(TheBeanBuiltFirst.class, TheBeanBuiltSecond.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(invocations).containsExactly("the early step", "the late step");

  }

  @Test
  void whatRanIsWrittenDown() {

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());

    final var records = mongoTemplate().findAll(ChangesetInformation.class);
    assertThat(records)
        .extracting(ChangesetInformation::getId)
        .containsExactlyInAnyOrder(
            TwoStepsOutOfOrder.class.getName()
                + "#first",
            TwoStepsOutOfOrder.class.getName()
                + "#second");

    final var first = recordOf(records, "#first");
    assertThat(first.getOrder()).isEqualTo(10);
    // the method names an author of its own, so the author of the bean is not used
    assertThat(first.getAuthor()).isEqualTo("somebody else");
    assertThat(first.getTimestamp()).isNotNull();

    final var second = recordOf(records, "#second");
    assertThat(second.getOrder()).isEqualTo(20);
    // the method names no author, so it takes the one of its bean
    assertThat(second.getAuthor()).isEqualTo("the team");

  }

  @Test
  void theIdentityOfAStepIsItsBeanClassAndItsMethodName() {

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());

    // the same step under a bean of another name is a step the database has never seen, which is
    // why renaming a changeset bean or one of its methods makes the step run again
    applicationHaving(TheSameStepUnderAnotherName.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(mongoTemplate().findAll(ChangesetInformation.class))
        .extracting(ChangesetInformation::getId)
        .contains(TheSameStepUnderAnotherName.class.getName()
            + "#first");

  }

  @DbChangesetConfiguration(author = "the team")
  static class TheSameStepUnderAnotherName {

    @DbChangeset(order = 10)
    public String first() {

      invocations.add("first, under another bean");
      return null;

    }

  }

  @Test
  void aRollbackScriptIsStoredWithTheStepThatReturnedIt() {

    applicationHaving(TwoStepsOutOfOrder.class)
        .run(context -> assertThat(context).hasNotFailed());

    final var records = mongoTemplate().findAll(ChangesetInformation.class);
    assertThat(recordOf(records, "#second").getRollbackScripts())
        .containsExactly("{ drop: 'letters' }");

    // a step with nothing to undo answers with null, and then no script is stored
    assertThat(recordOf(records, "#first").getRollbackScripts()).isEmpty();

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

    assertThat(mongoTemplate().findAll(ChangesetInformation.class))
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
            .hasMessageContaining("has to be either String or Collection<String>")
            .hasMessageContaining("#howManyDocumentsWereTouched"));

    assertThat(invocations).isEmpty();
    // the start is refused while the steps are collected, so nothing was written down
    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).isEmpty();

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

    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).isEmpty();

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepWhichBreaks {

    @DbChangeset(order = 5)
    public String createsNothing() {

      invocations.add("the step which breaks");
      throw new IllegalStateException("the database said no");

    }

  }

  @Test
  void aStepWhichFailsEndsTheStartAndLeavesNoRecordBehind() {

    applicationHaving(AStepWhichBreaks.class)
        .run(context -> assertThat(context)
            .hasFailed()
            .getFailure()
            .rootCause()
            .hasMessage("the database said no"));

    assertThat(invocations).containsExactly("the step which breaks");
    // the record was saved before the step ran and is taken back again, so the next start
    // tries the step once more instead of skipping a step which never happened
    assertThat(mongoTemplate().findAll(ChangesetInformation.class)).isEmpty();

  }

  /**
   * A bean of the application which may only be built once the database is migrated. It says so
   * by taking the applier as a parameter.
   */
  @Configuration(proxyBeanMethods = false)
  static class ABeanWhichNeedsTheMigration {

    @Bean
    public String theBeanBuiltAfterTheMigration(
        final ChangesetApplier applier) {

      invocations.add("the bean which needs the migration");
      return "built";

    }

  }

  @Test
  void aBeanWhichTakesTheApplierIsBuiltAfterTheMigration() {

    applicationHaving(TwoStepsOutOfOrder.class, ABeanWhichNeedsTheMigration.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(invocations)
        .containsExactly("first", "second", "the bean which needs the migration");

  }

  @DbChangesetConfiguration(author = "the team")
  static class AStepReadingTheRecordWrittenAboutIt {

    @DbChangeset(order = 1)
    public String looksAtItsOwnRecord(
        final MongoTemplate mongoTemplate) {

      final var ownRecord = mongoTemplate.findAll(ChangesetInformation.class).getFirst();
      timestampsSeenWhileTheStepsRan.add(ownRecord.getTimestamp());
      return null;

    }

  }

  @Test
  void theRecordCarriesTheTimeBeforeTheStepRuns() {

    applicationHaving(AStepReadingTheRecordWrittenAboutIt.class)
        .run(context -> assertThat(context).hasNotFailed());

    // a process which is killed inside a step leaves its record behind. That record says when
    // the step was started, so nobody has to guess whether it ran at all.
    assertThat(timestampsSeenWhileTheStepsRan)
        .singleElement()
        .isNotNull();

  }

  private static ChangesetInformation recordOf(
      final List<ChangesetInformation> records,
      final String idEndsWith) {

    return records
        .stream()
        .filter(record -> record.getId().endsWith(idEndsWith))
        .findFirst()
        .orElseThrow();

  }

}
