package com.phactum.mongodb.changesets;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.util.ReflectionUtils;

import com.mongodb.WriteConcern;
import com.phactum.mongodb.changesets.ChangesetProperties.MongoDbMode;

import jakarta.annotation.PostConstruct;

/**
 * Applies the MongoDB migration steps of the application while it starts.
 * <p>
 * It collects the steps from the beans annotated with {@link DbChangesetConfiguration} and sorts
 * them by the order each {@link DbChangeset} declares. Then it runs the ones this database has
 * not seen. What ran is written into the collection of {@link ChangesetInformation}, and a later
 * start reads that collection to know what to skip.
 * <p>
 * A step is saved before it is applied. A second node which starts at the same moment and gets
 * there first has saved the same record already. This node still has the step on its list, so
 * its own save is an insert and the id of that record is taken. MongoDB refuses it, which ends
 * this start instead of applying the step twice.
 * <p>
 * A bean which may only be built after the migration takes this one as a parameter. Spring builds
 * what a bean depends on first, so the migration has run by the time that bean is built. This is
 * the bean to ask for. The auto-configuration is not, because it exists before it declares this
 * one.
 * <p>
 * Two system properties exist for a developer. <code>initializer.rollback.all</code> rolls every
 * known step back and ends the process. <code>initializer.rollback.unknown</code> rolls back what
 * the database has and this software does not know any more, which is what a downgrade leaves
 * behind.
 *
 * @see DbChangeset
 * @see DbChangesetConfiguration
 */
public class ChangesetApplier {

  private static Logger logger = LoggerFactory.getLogger(ChangesetApplier.class);

  private static String SYSTEMPROPERTY_ROLLBACKALL = "initializer.rollback.all";

  private static String SYSTEMPROPERTY_ROLLBACK_UNKNOWN = "initializer.rollback.unknown";

  /**
   * What the migration writes with. The primary has the record and the record is in the journal
   * of that primary, on disk, before the step it belongs to runs.
   * <p>
   * The value names its <code>w</code>, and it has to. <code>WriteConcern.JOURNALED</code> of the
   * driver says the same wish with <code>w</code> left open, and Spring Data does not pass such a
   * value on: a MongoTemplate whose write result checking is <code>EXCEPTION</code> replaces every
   * write concern without a <code>w</code>, or with a <code>w</code> below one, by a plain
   * <code>ACKNOWLEDGED</code>, and the journal flag is dropped with it. An application which
   * checks its write results would get no promise at all. With <code>w: 1</code> the value reaches
   * the database untouched.
   * <p>
   * It is not <code>majority</code>. The promise is here for the node which wrote the record and
   * then dies, and the journal is what brings that record back. A failover in the middle of a step
   * is another matter: a step and the record about it are not one transaction, so no write concern
   * makes the two survive or vanish together. This is decision 5 in the repository's DECISIONS.md.
   */
  private static final WriteConcern WRITE_CONCERN_OF_THE_MIGRATION = WriteConcern.W1.withJournal(true);

  static class DbChangesetMethod {

    Object bean;

    Method reflectionMethod;

  }

  private ApplicationContext applicationContext;

  private MongoTemplate mongoTemplate;

  private ChangesetProperties properties;

  public ChangesetApplier(
      final ApplicationContext applicationContext,
      final MongoTemplate mongoTemplate,
      final ChangesetProperties properties) {

    this.applicationContext = applicationContext;
    this.mongoTemplate = mongoTemplate;
    this.properties = properties;

  }

  @PostConstruct
  public void init() {

    logger.info("About to apply MongoDb changesets...");

    // The template belongs to the application, so the promise above holds for the time of the
    // migration only and the value the application had is put back afterwards, also when a step
    // throws. That also puts back a value which was stronger than the one of the migration.
    final var writeConcernOfTheApplication = writeConcernOfTheApplication();
    mongoTemplate.setWriteConcern(WRITE_CONCERN_OF_THE_MIGRATION);
    try {

      initChangesetsCollection();

      final var changesets = buildMapSortedByChangesetOrder();

      collectChangesetsByAnnotationsOnBeans(changesets);

      rollbackAllIfNecessary();

      final var unknownChangesets = removeAlreadyAppliedChangesets(changesets);

      applyNewChangesets(changesets);

      rollbackUnknownChangesets(unknownChangesets);

    } finally {
      mongoTemplate.setWriteConcern(writeConcernOfTheApplication);
    }

    logger.info("Applying MongoDb changesets completed.");

  }

  /**
   * The write concern the application has on its template, or <code>null</code> where it set
   * none and the connection decides.
   * <p>
   * A MongoTemplate takes a write concern and hands none back, so the value is read from the
   * field its setter writes. Without it this library would either leave the connection of the
   * application changed for good or guess a value the application never asked for.
   */
  private WriteConcern writeConcernOfTheApplication() {

    final var writeConcern = ReflectionUtils
        .findField(MongoTemplate.class, "writeConcern", WriteConcern.class);
    if (writeConcern == null) {
      throw new IllegalStateException(
          "Cannot read the write concern of the MongoTemplate. This version of Spring Data "
              + "MongoDB keeps it somewhere else than in the field 'writeConcern', so the "
              + "migration cannot give the template back the way it got it.");
    }
    ReflectionUtils.makeAccessible(writeConcern);

    return (WriteConcern) ReflectionUtils.getField(writeConcern, mongoTemplate);

  }

  private void initChangesetsCollection() {

    if (mongoTemplate
        .getCollectionNames()
        .contains(ChangesetInformation.COLLECTION_NAME)) {
      return;
    }

    mongoTemplate
        .createCollection(ChangesetInformation.COLLECTION_NAME);

    // Azure Cosmos wants indexes for all fields ordered by
    if (properties.getMode() == MongoDbMode.AZURE_COSMOS_MONGO_4_2) {

      mongoTemplate
          .indexOps(ChangesetInformation.COLLECTION_NAME)
          .createIndex(new Index()
              .on("order", Direction.ASC)
              .named(ChangesetInformation.COLLECTION_NAME
                  + "_order"));

    }

  }

  private void rollbackAllIfNecessary() {

    if (!rollbackWasAskedFor(SYSTEMPROPERTY_ROLLBACKALL)) {
      return;
    }

    knownChangesets()
        .forEach(changeset -> rollbackChangeset(changeset,
            "Rolling back changeset '{}' due to system property "
                + SYSTEMPROPERTY_ROLLBACKALL));

    logger.info("Will exit due to system property {}", SYSTEMPROPERTY_ROLLBACKALL);
    System.exit(1);

  }

  private void rollbackUnknownChangesets(
      final List<ChangesetInformation> unknownChangesets) {

    if (!rollbackWasAskedFor(SYSTEMPROPERTY_ROLLBACK_UNKNOWN)) {
      return;
    }

    unknownChangesets
        .forEach(changeset -> rollbackChangeset(changeset,
            "Rolling back unknown changeset '{}' of previous software version"));

  }

  /**
   * Whether the given system property asks for a rollback.
   * <p>
   * Somebody types this on a command line, on the day something is wrong. So the value is read
   * without pedantry: upper case counts and a blank around it does too. Both properties are read
   * here, so both answer to the same spelling.
   */
  private static boolean rollbackWasAskedFor(
      final String systemProperty) {

    return Boolean.parseBoolean(
        System
            .getProperty(systemProperty, Boolean.FALSE.toString())
            .trim());

  }

  private void rollbackChangeset(
      final ChangesetInformation changeset,
      final String info) {

    try {
      logger.info(info, changeset.getId());

      // every record this mechanism writes carries a list, and a record somebody wrote into the
      // collection themselves carries none. There is nothing to run for such a record, and the
      // rollback goes on to remove it like any other one.
      final var rollbackScripts = changeset.getRollbackScripts();
      if (rollbackScripts != null) {
        rollbackScripts
            .stream()
            .map(Document::parse)
            .forEach(script -> mongoTemplate
                .execute(db -> db.runCommand(script)));
      }

      mongoTemplate
          .remove(changeset);
    } catch (Exception e) {
      logger.info(info
          + " failed!", changeset.getId(), e);
    }

  }

  private void applyNewChangesets(
      final Map<ChangesetInformation, DbChangesetMethod> changeSets) {

    changeSets
        .entrySet()
        .forEach(changeset -> applyNewChangeset(changeset.getKey(), changeset.getValue()));

  }

  @SuppressWarnings("unchecked")
  private void applyNewChangeset(
      final ChangesetInformation changeset,
      final DbChangesetMethod method) {

    logger.info("Applying new changeset '{}'", changeset.getId());

    // The time is written with the record and not after it. A process which is killed inside
    // the step leaves its record behind, and that record says when the step was started.
    changeset.setTimestamp(Instant.now());

    // The record of the step is saved before the step runs. Another node of the cluster which
    // starts at the same moment and is a little bit faster has saved the same record already.
    // This node still has the step on its list, so this save is an insert and the id is taken.
    // MongoDB refuses it with a duplicate key error, and that ends this start. Which is what
    // should happen: the other node is applying the step, and applying it twice is what has to
    // be avoided.
    final ChangesetInformation persistedChangeset = mongoTemplate
        .save(changeset);

    try {
      final var reflectionMethod = method.reflectionMethod;
      final Object rollbackScript;
      if (reflectionMethod.getParameterCount() == 1) {
        rollbackScript = reflectionMethod.invoke(method.bean, mongoTemplate);
      } else {
        rollbackScript = reflectionMethod.invoke(method.bean);
      }
      final List<String> rollbackScripts;
      if (rollbackScript == null) {
        rollbackScripts = List.of();
      } else if (rollbackScript instanceof Collection) {
        rollbackScripts = List.copyOf((Collection<String>) rollbackScript);
      } else {
        // the return type of the method was checked while the steps were collected, and what
        // passed that check and is no collection is a String
        rollbackScripts = List.of((String) rollbackScript);
      }
      persistedChangeset.setRollbackScripts(rollbackScripts);
    } catch (Exception e) {
      mongoTemplate
          .remove(persistedChangeset);

      throw new RuntimeException("Could not apply changeset '"
          + changeset.getId()
          + "'", e);
    }

    mongoTemplate
        .save(persistedChangeset);

  }

  private void collectChangesetsByAnnotationsOnBeans(
      final Map<ChangesetInformation, DbChangesetMethod> changeSetMethods) {

    final var changesetBeans = applicationContext
        .getBeansWithAnnotation(DbChangesetConfiguration.class);
    changesetBeans
        .entrySet()
        .forEach(beanEntry -> this.collectionChangesetsByMethodsOfAnnotatedBeans(
            changeSetMethods, beanEntry.getValue()));

  }

  private void collectionChangesetsByMethodsOfAnnotatedBeans(
      final Map<ChangesetInformation, DbChangesetMethod> changeSetMethods,
      final Object bean) {

    final var config = bean
        .getClass()
        .getAnnotation(DbChangesetConfiguration.class);

    Arrays
        .stream(bean.getClass().getMethods())
        .forEach(beanMethod -> collectionChangesetsByAnnotatedBeanMethods(
            changeSetMethods,
            config.author(),
            bean,
            beanMethod));

  }

  private void collectionChangesetsByAnnotatedBeanMethods(
      final Map<ChangesetInformation, DbChangesetMethod> changeSetMethods,
      final String defaultAuthor,
      final Object bean,
      final Method beanMethod) {

    final var changesetAnnotation = beanMethod.getAnnotation(DbChangeset.class);
    if (changesetAnnotation == null) {
      return;
    }

    final String author;
    if (changesetAnnotation.author().equals("")) {
      author = defaultAuthor;
    } else {
      author = changesetAnnotation.author();
    }
    final var information = new ChangesetInformation();
    information.setAuthor(author);
    final String changesetId = bean.getClass().getName()
        + "#"
        + beanMethod.getName();
    information.setId(changesetId);
    information.setOrder(changesetAnnotation.order());

    final var method = new DbChangesetMethod();
    method.bean = bean;
    method.reflectionMethod = beanMethod;

    final Class<?> returnType = method.reflectionMethod.getReturnType();
    final boolean isString = returnType.equals(String.class);
    final boolean isCollection = Collection.class.isAssignableFrom(returnType);
    if (!isString && !isCollection) {
      throw new RuntimeException(
          "Result type of changeset method '"
              + changesetId
              + "' has to be either String or Collection<String>, but is '"
              + returnType);
    }

    final var duplicates = changeSetMethods.keySet()
        .stream()
        .filter((
            key) -> key.getOrder() == information.getOrder())
        .collect(Collectors.toList());
    if (!duplicates.isEmpty()) {
      throw new RuntimeException(
          "Got at least two changesets having the same order value '"
              + information.getOrder()
              + "': '"
              + information.getId()
              + "' and '"
              + duplicates.iterator().next().getId()
              + "'!");
    }

    changeSetMethods.put(information, method);

  }

  private List<ChangesetInformation> knownChangesets() {

    final var query = new Query();
    query.with(Sort.by(Sort.Direction.DESC, "order"));

    return mongoTemplate
        .find(query, ChangesetInformation.class);

  }

  private List<ChangesetInformation> removeAlreadyAppliedChangesets(
      final Map<ChangesetInformation, DbChangesetMethod> changeSetMethods) {

    final var unkownChangesets = new LinkedList<ChangesetInformation>();

    knownChangesets()
        .forEach(alreadyAppliedChangeset -> {
          if (changeSetMethods.remove(alreadyAppliedChangeset) == null) {
            unkownChangesets.add(alreadyAppliedChangeset);
          }
        });

    return unkownChangesets;

  }

  private Map<ChangesetInformation, DbChangesetMethod> buildMapSortedByChangesetOrder() {

    return new TreeMap<>(
        new Comparator<ChangesetInformation>() {
          @Override
          public int compare(
              final ChangesetInformation o1,
              final ChangesetInformation o2) {

            if (o1.equals(o2)) {
              return 0;
            }
            if (o1.getOrder() != o2.getOrder()) {
              return o1.getOrder() < o2.getOrder() ? -1 : 1;
            }
            return o1.getId().compareTo(o2.getId());

          }
        });

  }

}
