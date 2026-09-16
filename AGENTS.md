# Working on mongodb-changesets

A database migration for MongoDB, driven by annotated methods of Spring beans. Read
[`README.md`](./README.md) first. It says what the mechanism does, what a step may answer with and
how an application configures it.

## This library knows nothing about its users

The code here talks to Spring Boot and to MongoDB, and to nothing else. It came out of a larger
application, and the point of the move was that it stops being a part of one.

So no name of an application appears in this repository, in the code, in the documentation or in
the artifact coordinates, unless it is an example and reads like one. The same goes for a
configuration key: a key belongs to this library or it does not belong here.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places in this repository rely on. It
is the ONLY thing the code is allowed to cite, in the plain greppable form
`see decision 3 in the repository's DECISIONS.md`, and only entries of THIS repository.

Read it before you change behaviour. An entry is not background reading, it is the reason the code
around it looks the way it does, so a change which contradicts one is wrong until the entry says
otherwise.

**A decision is changed or replaced only after asking.** Where your change would make an entry
untrue, stop and put the question to the maintainer before you write the change. If the answer is
yes, the same commit updates the log: the old entry STAYS, marked as superseded and naming the
entry which replaced it, and the new decision takes the next free number. Numbers are never reused
and never renumbered, because a citation in an older release still points at them.

Adding an entry has the same rule. A decision earns a number when several places rely on it and
copying the explanation to each of them would rot; anything smaller is a comment where it belongs,
and anything larger is documentation.

## How we write

Most people who read this repository read English as a second language, and so does the
maintainer. Long sentences, rare words and stacked nouns slow them down. Write so that
nobody has to read a sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice.
The common word instead of the rare one: `use` instead of `leverage`, `about` instead of
`regarding`, `so` instead of `consequently`, `has` instead of `possesses`. A technical term
stays a technical term, but say what it means the first time it turns up, and write an
abbreviation out once. If a sentence trips you up when you read it aloud, rewrite it.

This holds for every English text here: `README.md`, `DECISIONS.md`, this file, the Javadoc and
comments which explain something, and the texts of commits and pull requests.

Nothing a program reads is renamed for the sake of language. Class and method names,
configuration keys and artifact coordinates stay as they are, because code, tests and other
projects point at them.

## What code may point at

Nothing which a later change can invalidate without anything noticing: no story or prompt number,
no issue or pull-request number, no chat transcript, no person. Those record a conversation at a
point in time. A decision entry lives next to the code and is overhauled in the same commit, which
is what makes it citable.

Where a name can carry the reason, the name is the better fix. Where it cannot, a comment says why
in its own words, complete where it stands. Only what several places have to carry becomes an entry
in the log.

Commit messages and pull-request descriptions may cite whatever they like. They are records of a
point in time themselves.

## Before you open a pull request

A number your branch hands out can be taken by the time you open the pull request. Another branch
was open at the same time and got there first. So check your decision numbers against
`origin/main` and against every open pull request, before the pull request exists:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^#+ [0-9]+\. '   # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+#+ [0-9]+\. '                    # for each open pull request
```

`gh pr diff` takes no path argument, so the grep does the filtering.

If your number is taken, your entry gets the next free one, and you correct every citation of it
in the code and in the documentation. Read each citation before you change it. A branch can cite a
number somebody else handed out long ago, and that citation stays as it is.

## Building and formatting

```bash
mvn install
mvn spotless:apply
```

Run `spotless:apply` before every commit. It formats the POM and the Markdown as well as the Java,
and the build fails on a violation.

## What the build publishes

A push to main publishes the snapshot to the GitHub Packages of this repository. The token GitHub
hands the run is enough for that, so the build and the publish read no secret of their own.

The release workflow follows the sibling repositories and uploads to Maven Central. It needs
credentials which an administrator has to create first, and the comments in
[`.github/workflows/release.yaml`](./.github/workflows/release.yaml) name them.
