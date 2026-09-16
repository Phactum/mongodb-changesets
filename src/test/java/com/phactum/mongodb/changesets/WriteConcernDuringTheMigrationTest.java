package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.WriteConcern;

/**
 * What the migration does to the write concern of the application.
 * <p>
 * A record of the migration says that a step ran. It has to survive a node which dies right
 * after writing it, so the migration writes journaled. The template it writes through belongs to
 * the application, so the stricter promise lasts for the migration only and the value the
 * application had is put back afterwards.
 * <p>
 * A MongoTemplate takes a write concern and hands none back, so the template of these tests
 * writes down what the library sets on it.
 */
class WriteConcernDuringTheMigrationTest extends AgainstARealMongoDb {

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
  void theMigrationWritesJournaledAndGivesTheTemplateBackAsItWas() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());
    // this is what the application asked for, long before the migration
    template.setWriteConcern(WriteConcern.W1);

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(whatTheStepsSaw).containsExactly(WriteConcern.JOURNALED);
    assertThat(template.currentValue()).isEqualTo(WriteConcern.W1);
    assertThat(template.valuesSet())
        .containsExactly(WriteConcern.W1, WriteConcern.JOURNALED, WriteConcern.W1);

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

    assertThat(whatTheStepsSaw).containsExactly(WriteConcern.JOURNALED);
    assertThat(template.currentValue()).isEqualTo(WriteConcern.W1);

  }

  @Test
  void anApplicationWhichSetNoWriteConcernHasNoneAfterwards() {

    final var template = new TemplateWritingDownItsWriteConcern(databaseFactory());

    applicationOf(template, AStepLookingAtTheWriteConcern.class)
        .run(context -> assertThat(context).hasNotFailed());

    // the application left the write concern of its template unset, so the connection decides
    // again once the migration is done
    assertThat(template.valuesSet()).containsExactly(WriteConcern.JOURNALED, null);

  }

}
