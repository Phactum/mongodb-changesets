# Decisions

The decisions several places in this repository rely on. Each one keeps its number forever, so a
comment in the code can point at it. Read [`AGENTS.md`](./AGENTS.md) for what that means for a
change which would make an entry untrue.

## 1. The keys live under `mongodb.changesets`, and no prefix is bound strictly

`ChangesetProperties` reads the prefix `mongodb.changesets`. It does not use
`ignoreUnknownFields = false`.

A binding with `ignoreUnknownFields = false` refuses every key under its prefix which none of its
fields takes, and a key of a nested prefix counts as one of those. Spring Boot answers with
`UnboundConfigurationPropertiesException: The elements [mongodb.changesets.mode] were left
unbound.` and the application does not start.

So two things follow. This project stays lenient, because a project which one day nests a prefix
under `mongodb.changesets` should not be stopped by us. And an application which binds `mongodb`
strictly has to make room for the nested key, which is its own change and not one this library can
make.

## 2. The mode keeps the literals it had before the move

`MongoDbMode` names its values `MONGODB_4_8` and `AZURE_COSMOS_MONGO_4_2`. The names are odd for a
fresh library, because they carry version numbers which say nothing about what the value does.

They stay because this code comes out of an application which already ships with these values in
its configuration files. An operator moves the key and keeps the value. Renaming the literals would
have made a working configuration a broken one, for nothing but a nicer word.

## 3. The time a step ran is an `Instant`

`ChangesetInformation.timestamp` is a `java.time.Instant` and not an `OffsetDateTime`.

Spring Data MongoDB converts an `Instant` on its own. For an `OffsetDateTime` it needs a converter
which the application has to register, and without one the start fails with `Can't find a codec for
java.time.OffsetDateTime`. The application this code comes from has such a converter, so the
problem only shows up once the code is a library.

What reaches the document is a BSON date either way, so a collection written before the move reads
back without a migration of its own.

## 4. One artifact, and no platform tag in its name yet

The auto-configuration is Spring Boot, and the artifact is called `mongodb-changesets`, without a
platform in the name.

A second platform, Quarkus for example, would be a module of this repository, named
`mongodb-changesets-quarkus`, and the part which knows no platform would move into a module of its
own. That cut is not made today, because a cut made for a platform which does not exist is a guess
about what that platform will need.

## 5. The migration writes with `w: 1` and the journal, and it names the `w` on purpose (superseded by decision 6)

Superseded by decision 6. What follows is what this repository held before, and it is kept because the number stays readable for a release which cites it.

While a migration runs, the library writes with `WriteConcern.W1.withJournal(true)`, and it puts
the value of the application back afterwards.

The journal is the point. A record says that a step ran, and it has to survive the node which
wrote it and then died; the journal is what brings it back. The `w` is named because Spring Data
throws the promise away otherwise. `MongoTemplate.potentiallyForceAcknowledgedWrite` replaces
every write concern whose `w` is unset with a plain acknowledged write as soon as the application
checks its write results, and the driver leaves a server-default concern out of the command
altogether. `WriteConcern.JOURNALED` is `j: true` with no `w`, so such an application used to send
no promise at all. Naming `w: 1` is what makes the journal reach the database.

It is not `majority`, and that is not thrift. A step and the record about it are not one
transaction, and the record is written before the step runs. A rollback which drops the record
drops the work of the step with it, which is the harmless direction. The harmful one is a record
which survives while the change is gone, and `majority` on the record makes exactly that more
likely, because a majority-committed record is never rolled back while the writes of the step
after it still can be. So `majority` would be stricter in the wrong half, and it would make every
record wait for replication.

What the library does not do is raise the promise of an application which asked for more. An
application writing with `majority` migrates with `w: 1` for the length of the migration. Whether
the promise of the migration should grow out of the one the application set is written down as its
own question.

## 6. The write concern of the migration grows out of the one the application uses

The library reads what the application writes with, adds `j: true` to it and keeps the `w`. An
application writing with `majority` migrates with `majority` and the journal. Where the
application promises nothing to grow from, the migration writes with `w: 1` and the journal, and
that is also what happens with `w: 0`, because a write nobody answers tells the next step nothing.
The value the template carried is put back when the last step is done, and a template which
carried none carries none again.

The promise of the application is not only the value on the template. A connection string like
`mongodb://host/db?w=majority` leaves that value empty while every write still asks for a
majority, so the library reads the collection where the template says nothing.

The reason for growing instead of replacing is that a library must not write weaker than the
application it runs in. The old decision replaced the promise of the application with a fixed
`w: 1` for the length of the migration. An application writing with `majority` then had its
migration written weaker than everything else it does, which is a surprise nobody asked for.

The journal is still the point of the promise. A record says that a step ran, and it has to
survive the node which wrote it and then died. The `w` is spelled out for the same reason as
before: `MongoTemplate.potentiallyForceAcknowledgedWrite` replaces every write concern whose `w`
is unset, or below one, by a plain acknowledged write as soon as the application checks its write
results, and the driver leaves a server-default concern out of the command altogether. A word like
`majority` passes that check untouched, so the grown promise reaches the database.

What the library does not do is raise the promise on its own. A fixed `majority` with the journal
for every migration was the other candidate, and it was turned down for four reasons.

`majority` is no upper bound. A write concern mode can ask for more than a majority, because a
majority may sit in one data centre and a mode may forbid exactly that, and in a set of seven
nodes `w: 5` is stricter than the majority of four. So a fixed value would not even give what it
was picked for. The calculation does, because it starts from what the application asks for and
compares nothing.

Stricter on the record is not better. The record is written before the step runs, and the two are
not one transaction. A rollback which drops the record drops the work of the step with it, which
is the harmless direction. The harmful one is a record which survives while the change is gone,
and a record written with more than the step makes that more likely. This is why the library
raises nothing by itself. It only refuses to lower.

The price is paid while the application starts. `majority` with the journal waits for replication
per record, and without a `wtimeout` it waits without end. A set which lost a node, where the
application itself would write with `w: 1`, would hold up the start with a cost its operator never
chose.

The Azure Cosmos mode may not take a `majority`, and nothing here proves that it does. The
calculation passes on what the application itself uses, so it cannot ask that database for
something the application knows it cannot have.
