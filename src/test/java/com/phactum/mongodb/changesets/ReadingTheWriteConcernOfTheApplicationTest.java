package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoAction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.WriteConcernResolver;

import com.mongodb.WriteConcern;

/**
 * The one place where this library reaches inside Spring Data MongoDB.
 * <p>
 * The migration grows its promise out of the one the application writes with, and it gives the
 * template back the way it got it. Both need the value the application set on its MongoTemplate.
 * Spring Data MongoDB takes that value and hands it back to nobody, so
 * {@link ChangesetApplier#writeConcernSetOn(MongoTemplate)} reads the private field the setter
 * writes.
 * <p>
 * A new Spring Data MongoDB can take that field away or call it something else, and then this
 * library is wrong. These tests are where that shows up. They run on every build, so an upgrade
 * of Spring Data MongoDB fails here instead of failing in an application while it starts.
 * <p>
 * Two things are tested, and they are not the same. That the field can be read at all is one.
 * That the field is the one the template writes with is the other, and only a write says that. A
 * Spring Data MongoDB which keeps the field and stops using it would pass the first test while
 * this library reads a value nothing acts on.
 */
class ReadingTheWriteConcernOfTheApplicationTest extends AgainstARealMongoDb {

  private static final String A_COLLECTION_OF_THIS_TEST = "customer";

  /**
   * What the template hands to its write concern resolver before a write.
   * <p>
   * Spring Data MongoDB asks the resolver what to write with, and it gives the resolver the value
   * of the template to answer from. So this is the same value seen from the other side, through
   * an interface which is meant to be used, and a test can hold the two against each other.
   * <p>
   * The answer is not the value which was handed over. A test wants to try a promise which this
   * one server cannot keep, and an answer the server always keeps lets it do that without the
   * write failing for a reason the test is not about.
   */
  static class WhatTheTemplateWritesWith implements WriteConcernResolver {

    private final List<WriteConcern> valuesHandedOver = new ArrayList<>();

    @Override
    public WriteConcern resolve(
        final MongoAction action) {

      valuesHandedOver.add(action.getDefaultWriteConcern());
      return WriteConcern.W1;

    }

    List<WriteConcern> valuesHandedOver() {

      return valuesHandedOver;

    }

  }

  /**
   * Writes once through a template of an application which promises the given value, and says
   * what the template handed to its resolver while doing so.
   * <p>
   * The value this library reads out of the template is checked here, because every test wants
   * the same answer to it: what went in comes back out.
   */
  private WhatTheTemplateWritesWith writeThroughATemplatePromising(
      final WriteConcern whatTheApplicationPromises) {

    final var template = new MongoTemplate(databaseFactory());
    template.setWriteConcern(whatTheApplicationPromises);
    final var whatTheTemplateWritesWith = new WhatTheTemplateWritesWith();
    template.setWriteConcernResolver(whatTheTemplateWritesWith);

    template.insert(new Document("_id", "one"), A_COLLECTION_OF_THIS_TEST);

    assertThat(ChangesetApplier.writeConcernSetOn(template))
        .isEqualTo(whatTheApplicationPromises);

    return whatTheTemplateWritesWith;

  }

  @Test
  void anApplicationWhichSetNoPromiseIsReadAsHavingNone() {

    final var whatTheTemplateWritesWith = writeThroughATemplatePromising(null);

    // an empty field means that the connection decides, and the library has to see the
    // difference, because it reads the promise of the connection somewhere else
    assertThat(whatTheTemplateWritesWith.valuesHandedOver())
        .isNotEmpty()
        .containsOnlyNulls();

  }

  @Test
  void thePromiseOfTheApplicationIsReadBackTheWayItWasSet() {

    final var whatTheTemplateWritesWith = writeThroughATemplatePromising(WriteConcern.MAJORITY);

    // the field the library reads is the field the template writes with
    assertThat(whatTheTemplateWritesWith.valuesHandedOver())
        .isNotEmpty()
        .containsOnly(WriteConcern.MAJORITY);

  }

  @Test
  void aPromiseNoneOfTheKnownConstantsCarriesIsReadBackToo() {

    // an application may name a write concern mode of its cluster, and the migration passes such
    // a name on untouched. So the value has to come back as it went in, and not as something
    // which only looks like it.
    final var whatTheApplicationPromises = new WriteConcern("aNameOnlyThisClusterKnows");

    final var whatTheTemplateWritesWith = writeThroughATemplatePromising(whatTheApplicationPromises);

    assertThat(whatTheTemplateWritesWith.valuesHandedOver())
        .isNotEmpty()
        .containsOnly(whatTheApplicationPromises);

  }

}
