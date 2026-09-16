# MongoDB Changesets

A database migration for MongoDB, written in Java. Flyway and Liquibase do this for a relational
database. This does it for MongoDB, and a migration step is a method of a Spring bean rather than
a file.

The library is small on purpose. It knows which steps a database has already seen, it runs the
rest in the order you gave them, and it writes down what it ran.

## What you add to your application

Add the dependency:

```xml
<dependency>
  <groupId>com.phactum.mongodb</groupId>
  <artifactId>mongodb-changesets</artifactId>
  <version>1.0.0</version>
</dependency>
```

The library is a Spring Boot auto-configuration. Your application needs a `MongoTemplate`, which
`spring-boot-starter-data-mongodb` gives it. Nothing else has to be switched on.

## A changeset bean

A bean which holds migration steps carries `@DbChangesetConfiguration`. Each step is a public
method of that bean and carries `@DbChangeset`.

```java
@Component
@DbChangesetConfiguration(author = "jane")
public class CustomerChangesets {

    @DbChangeset(order = 1)
    public String createTheCustomerCollection(final MongoTemplate mongoTemplate) {

        mongoTemplate.createCollection("customer");
        return "{ drop: 'customer' }";

    }

    @DbChangeset(order = 2, author = "john")
    public String indexTheCustomerNumber(final MongoTemplate mongoTemplate) {

        mongoTemplate
                .indexOps("customer")
                .createIndex(new Index().on("number", Direction.ASC).named("customer_number"));
        return "{ dropIndexes: 'customer', index: 'customer_number' }";

    }

}
```

A step takes a `MongoTemplate` as its only parameter, or no parameter at all. The `author` of the
bean is used for every step which names none of its own.

## When a step runs

While the application starts, and before the MongoDB repositories of the application are set up.
So a repository is never used on a database which has not been migrated yet.

Some other bean of yours may also need the migration to be done before it is built. Give that bean
a `ChangesetApplier` parameter. Spring builds what a bean depends on first, so the migration has
run by the time your bean is built. Take the applier and not `ChangesetAutoConfiguration`. A
configuration class is built before the beans it declares, so asking for it guarantees nothing, and
nothing tells you that.

A step runs once per database. What ran is stored in the collection `ChangesetInformation`, one
document per step, holding the author, the order, the time it ran and the rollback scripts it
answered with. A later start reads that collection and skips what is in it.

The identity of a step is the class name of its bean plus the name of its method. Renaming either
of the two makes the step run again on a database which already has it.

The step is written down before it runs. A second node of the cluster which starts at the same
moment and is a little bit faster has written the same document already, so this write fails on
the optimistic lock. That ends the second start, and the step is not applied twice.

## How the order is decided

By the `order` of `@DbChangeset`, and by nothing else. The number is read across every changeset
bean of the application, not per bean, so a step of one bean can sit between two steps of another.
The position of a method in its class means nothing.

Two steps which declare the same number end the start with an error. There would be no answer to
which of them runs first, and a migration whose order depends on the day is not a migration.

## What a step answers with

A step answers with a MongoDB command which undoes what it did, so that a rollback has something
to run. Write the command the way the
[MongoDB database command reference](https://docs.mongodb.com/manual/reference/command/) spells
it, for example `{ drop: 'customer' }`.

The return type has to be a `String` or a `Collection` of `String`. Answer with a collection where
one command is not enough, and with `null` where there is nothing to undo. Any other return type
is refused while the application starts, so a step with the wrong signature is found on the first
start and not on the day of the rollback.

## Rolling back

A rollback is a thing a developer does, not something that happens by itself. Two system
properties start one, and both of them are meant for a development machine.

`-Dinitializer.rollback.all=true` runs the rollback scripts of every step the database knows,
newest first, and then ends the process. The application does not come up.

`-Dinitializer.rollback.unknown=true` runs the rollback scripts of the steps the database knows
and this build of the software does not. That is what a downgrade leaves behind. The application
comes up afterwards.

A script which fails is logged and the rollback goes on. A rollback is a repair, and stopping in
the middle of one leaves the database in a worse state than finishing it.

## Telling the library which MongoDB it talks to

Azure Cosmos DB offers a MongoDB API, and it answers a query which sorts by a field without an
index with an error. A MongoDB server answers the same query. The library sorts the changeset
collection by `order`, so on Azure Cosmos DB it creates that index while it creates the
collection.

Say which of the two you are on:

```yaml
mongodb:
  changesets:
    mode: AZURE_COSMOS_MONGO_4_2   # the default is MONGODB_4_8
```

The index is created together with the collection. Switching the mode on a database which already
has the collection changes nothing, so set it before the first start against Azure Cosmos DB.

An application which holds this information somewhere else already can publish a bean of
`ChangesetProperties` instead of setting the key. The auto-configuration only builds one if the
application has none.

One thing to know about the key. The prefix `mongodb.changesets` sits below `mongodb`. If your
application binds `mongodb` with `@ConfigurationProperties(ignoreUnknownFields = false)`, that
binding refuses every key below `mongodb` which none of its own fields takes, and
`mongodb.changesets.mode` is such a key. Your application then does not start. Either give that
class a field for `changesets`, or drop `ignoreUnknownFields = false`, or publish the
`ChangesetProperties` bean and leave the key unset.

## Building

```bash
mvn install
```

Java 21 and Spring Boot 4 are what this builds against.

Run `mvn spotless:apply` before you commit. The build fails on a formatting violation.
