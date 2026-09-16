package com.phactum.mongodb.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.IndexInfo;

import com.phactum.mongodb.changesets.ChangesetProperties.MongoDbMode;

/**
 * What the configured mode does.
 * <p>
 * Azure Cosmos DB answers a query which sorts by a field without an index with an error, and a
 * MongoDB server answers the same query. The mechanism sorts the changeset collection by
 * <code>order</code>, so on Azure Cosmos DB it creates that index while it creates the
 * collection.
 * <p>
 * The tests run against a MongoDB server in both modes. What they say is what the mode does to
 * the collection, and that is the whole difference between the two.
 */
class ChangesetModeTest extends AgainstARealMongoDb {

  private static final String INDEX_ON_ORDER = ChangesetInformation.COLLECTION_NAME
      + "_order";

  @DbChangesetConfiguration(author = "the team")
  static class OneStep {

    @DbChangeset(order = 1)
    public String nothingToUndo() {

      return null;

    }

  }

  @Test
  void theDefaultModeIsAPlainMongoDb() {

    applicationHaving(OneStep.class)
        .run(context -> assertThat(context.getBean(ChangesetProperties.class).getMode())
            .isEqualTo(MongoDbMode.MONGODB_4_8));

    assertThat(indexNames()).doesNotContain(INDEX_ON_ORDER);

  }

  @Test
  void theModeIsReadFromTheConfiguration() {

    applicationHaving(OneStep.class)
        .withPropertyValues(ChangesetProperties.PREFIX
            + ".mode=AZURE_COSMOS_MONGO_4_2")
        .run(context -> assertThat(context.getBean(ChangesetProperties.class).getMode())
            .isEqualTo(MongoDbMode.AZURE_COSMOS_MONGO_4_2));

    assertThat(indexNames()).contains(INDEX_ON_ORDER);

  }

  @Test
  void anApplicationMayBringItsOwnConfigurationBean() {

    final var ownProperties = new ChangesetProperties();
    ownProperties.setMode(MongoDbMode.AZURE_COSMOS_MONGO_4_2);

    applicationHaving(OneStep.class)
        .withBean(ChangesetProperties.class, () -> ownProperties)
        .run(context -> {
          assertThat(context).hasSingleBean(ChangesetProperties.class);
          assertThat(context.getBean(ChangesetProperties.class)).isSameAs(ownProperties);
        });

    assertThat(indexNames()).contains(INDEX_ON_ORDER);

  }

  @Test
  void theModeOnlyCountsOnTheStartWhichCreatesTheCollection() {

    applicationHaving(OneStep.class)
        .run(context -> assertThat(context).hasNotFailed());

    // the collection is there now, so the second start creates neither it nor the index. An
    // operator who switches to Azure Cosmos DB has to set the mode before the first start.
    applicationHaving(OneStep.class)
        .withPropertyValues(ChangesetProperties.PREFIX
            + ".mode=AZURE_COSMOS_MONGO_4_2")
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(indexNames()).doesNotContain(INDEX_ON_ORDER);

  }

  private List<String> indexNames() {

    return mongoTemplate()
        .indexOps(ChangesetInformation.COLLECTION_NAME)
        .getIndexInfo()
        .stream()
        .map(IndexInfo::getName)
        .toList();

  }

}
