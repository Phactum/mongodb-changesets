package com.phactum.mongodb.changesets;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a changeset bean as one step of the MongoDb migration. The method takes a
 * MongoTemplate as its only parameter, or no parameter at all.
 * <p>
 * A step runs once per database. What ran is stored under the class name of the bean and the
 * name of the method, so renaming either of the two makes it another step. Where the database
 * applied the old name under the same order, the start ends, see {@link #order()}.
 * <p>
 * The step writes with the promise of the migration while it writes through the MongoTemplate it
 * was handed. A collection it takes out of that template, and a client it opens itself, write
 * with what their connection asks for. That can be less than what the record about the step was
 * written with, and such a record survives a failover which the work of the step does not.
 * <p>
 * The method answers with a MongoDb script which undoes what it did, or with a
 * java.util.List of scripts where one is not enough. A rollback runs those scripts. The
 * return type has to be a String or a Collection of Strings, and a step with nothing to undo
 * answers with null.
 * <p>
 * For the syntax of a rollback script see https://docs.mongodb.com/manual/reference/command/.
 * Two examples:
 * <ol>
 * <li>Drop collection: <pre>{ drop: 'mycollection' }</pre></li>
 * <li>Drop index: <pre>{ drop: 'mycollection', index = 'myindex' }</pre></li>
 * </ol>
 *
 * @see DbChangesetConfiguration
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DbChangeset {

  /**
   * Who wrote this step. A step which leaves this empty takes the author of its bean.
   *
   * @see DbChangesetConfiguration#author()
   */
  String author() default "";

  /**
   * The order is the identity of a step and its position. The steps run sorted by it, read
   * across all changeset beans of the application. And once a database applied a step, the number
   * belongs to that step for good.
   * <p>
   * So the start ends before any step runs when two steps declare the same number, when the
   * database applied another step under this number, or when the database applied this step
   * under another number. Gaps are fine, and a new step may take a free number below the applied
   * ones. A record of a step the software does not know any more keeps its number taken only for
   * the steps in the code, and it is no conflict while no step uses that number. This is decision
   * 8 in the repository's DECISIONS.md.
   */
  int order();

}
