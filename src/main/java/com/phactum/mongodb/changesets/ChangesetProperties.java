package com.phactum.mongodb.changesets;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the changeset mechanism has to know about the database it migrates.
 * <p>
 * An application sets these keys in its own configuration, under the prefix
 * <code>mongodb.changesets</code>. An application which already holds the same information
 * somewhere else can instead publish a bean of this class. The auto-configuration only creates
 * one if the application has none.
 *
 * @see ChangesetAutoConfiguration
 */
// The prefix is not bound with 'ignoreUnknownFields = false'. Such a binding rejects every key
// under its prefix which no field of the class takes, and a key of a nested prefix counts as
// one of those. So a strict class on 'mongodb' would fail the start of any application which
// sets 'mongodb.changesets.mode'. Being strict here would put the next project which nests a
// prefix under 'mongodb.changesets' into the same corner.
@ConfigurationProperties(prefix = ChangesetProperties.PREFIX)
public class ChangesetProperties {

  /**
   * The prefix of every key read by this class.
   */
  public static final String PREFIX = "mongodb.changesets";

  /**
   * Which kind of MongoDB the application talks to.
   */
  public enum MongoDbMode {

    /**
     * A MongoDB server. It sorts a query by any field, whether the field is indexed or not.
     */
    MONGODB_4_8,

    /**
     * Azure Cosmos DB through its MongoDB API, version 4.2. It answers a query which sorts
     * by a field without an index with an error, so the changeset collection gets an index
     * on the field it is sorted by.
     */
    AZURE_COSMOS_MONGO_4_2

  }

  private MongoDbMode mode = MongoDbMode.MONGODB_4_8;

  public MongoDbMode getMode() {
    return mode;
  }

  public void setMode(
      MongoDbMode mode) {
    this.mode = mode;
  }

}
