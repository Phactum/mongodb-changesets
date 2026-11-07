package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A new step whose order is below a step the database applied already runs out of order on that
 * database. A repair may want exactly that, and a number typed too low does it by mistake. The
 * start goes on in both cases, and the log names the step.
 */
class ANewChangesetBelowAnAppliedOneTest extends AgainstARealMongoDb {

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
  static class WhatTheNewSoftwareAdds {

    @DbChangeset(order = 15)
    public String createTheWords(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("words");
      return "{ drop: 'words' }";

    }

    @DbChangeset(order = 30)
    public String createTheSentences(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("sentences");
      return "{ drop: 'sentences' }";

    }

  }

  private final Logger logger = (Logger) LoggerFactory.getLogger(ChangesetApplier.class);

  private final ListAppender<ILoggingEvent> log = new ListAppender<>();

  @BeforeEach
  void recordTheLog() {

    log.start();
    logger.addAppender(log);

  }

  @AfterEach
  void stopRecordingTheLog() {

    logger.detachAppender(log);

  }

  @Test
  void theStepBelowTheHighestAppliedOneRunsAndIsNamedInTheLog() {

    applicationHaving(TheOldSoftware.class)
        .run(context -> assertThat(context).hasNotFailed());
    assertThat(linesAboutTheOrder()).isEmpty();

    applicationHaving(TheOldSoftware.class, WhatTheNewSoftwareAdds.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(mongoTemplate().getCollectionNames()).contains("words", "sentences");
    assertThat(linesAboutTheOrder())
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.INFO);
          assertThat(event.getFormattedMessage())
              .contains(WhatTheNewSoftwareAdds.class.getName()
                  + "#createTheWords")
              .contains("order 15")
              .contains("order 20")
              .contains(TheOldSoftware.class.getName()
                  + "#createTheNumbers");
        });

  }

  @Test
  void aNewDatabaseRunsEveryStepInItsPlaceAndSaysNothingAboutTheOrder() {

    applicationHaving(TheOldSoftware.class, WhatTheNewSoftwareAdds.class)
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(linesAboutTheOrder()).isEmpty();

  }

  private List<ILoggingEvent> linesAboutTheOrder() {

    return log.list
        .stream()
        .filter(event -> event.getFormattedMessage().contains("out of order"))
        .toList();

  }

}
