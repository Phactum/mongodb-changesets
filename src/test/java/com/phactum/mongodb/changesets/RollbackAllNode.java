package com.phactum.mongodb.changesets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

/**
 * An application which starts with <code>initializer.rollback.all</code>, in a process of its
 * own.
 * <p>
 * That property ends the process with {@link System#exit(int)} once the rollback is done. A test
 * cannot let that happen in the JVM which runs the tests, because the exit takes the whole run
 * with it. So the test starts this class as a second process and reads its exit code.
 * <p>
 * The process gets the class path of the test run, which is why the Surefire plugin is told to
 * write the real class path into <code>java.class.path</code>.
 */
public final class RollbackAllNode {

  /**
   * The one step this node knows. It creates a collection and answers with the command which
   * drops it again, so a rollback has something to do and the test has something to look at.
   */
  @DbChangesetConfiguration(author = "the team")
  public static class ChangesetsOfThatNode {

    @DbChangeset(order = 10)
    public String createTheLetters(
        final MongoTemplate mongoTemplate) {

      mongoTemplate.createCollection("letters");
      return "{ drop: 'letters' }";

    }

  }

  private RollbackAllNode() {
  }

  /**
   * Starts the node against the given database. The caller waits for it and reads the exit code.
   *
   * @param connectionString where the node finds the database
   * @return the running process
   * @throws IOException when the process cannot be started
   */
  public static Process startAgainst(
      final String connectionString) throws IOException {

    final var java = Path
        .of(System.getProperty("java.home"), "bin", "java")
        .toString();

    final var command = List.of(
        java,
        "-cp",
        System.getProperty("java.class.path"),
        "-Dinitializer.rollback.all=true",
        RollbackAllNode.class.getName(),
        connectionString);

    // what the node logs belongs to the test run, so it goes where the test run's own output
    // goes. A failing node is read there and nowhere else.
    return new ProcessBuilder(command)
        .inheritIO()
        .start();

  }

  /**
   * @param args the connection string of the database to roll back, and nothing else
   * @throws Exception when the connection cannot be closed again
   */
  public static void main(
      final String[] args) throws Exception {

    final var databaseFactory = new SimpleMongoClientDatabaseFactory(args[0]);
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ChangesetAutoConfiguration.class))
          .withBean(MongoTemplate.class, () -> new MongoTemplate(databaseFactory))
          .withUserConfiguration(ChangesetsOfThatNode.class)
          .run(context -> {
          });
    } finally {
      databaseFactory.destroy();
    }

  }

}
