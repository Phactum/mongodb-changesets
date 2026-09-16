package com.phactum.mongodb.changesets;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(ChangesetInformation.COLLECTION_NAME)
public class ChangesetInformation {

  public static final String COLLECTION_NAME = "ChangesetInformation";

  @Id
  private String id;

  @Version
  private long version;

  private String author;

  // Instant and not OffsetDateTime. Spring Data MongoDB converts an Instant on its own, and an
  // OffsetDateTime only if the application registers a converter for it. A library which needs
  // such a converter fails with "Can't find a codec for java.time.OffsetDateTime" in every
  // application which does not have one. What ends up in the document is the same either way, a
  // BSON date.
  private Instant timestamp;

  private int order;

  private List<String> rollbackScripts;

  public String getId() {
    return id;
  }

  public void setId(
      String id) {
    this.id = id;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(
      String author) {
    this.author = author;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(
      Instant timestamp) {
    this.timestamp = timestamp;
  }

  public int getOrder() {
    return order;
  }

  public void setOrder(
      int order) {
    this.order = order;
  }

  public List<String> getRollbackScripts() {
    return rollbackScripts;
  }

  public void setRollbackScripts(
      List<String> rollbackScripts) {
    this.rollbackScripts = rollbackScripts;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(
      long version) {
    this.version = version;
  }

  @Override
  public boolean equals(
      Object obj) {
    if (!(obj instanceof ChangesetInformation)) {
      return false;
    }
    return ((ChangesetInformation) obj).getId().equals(getId());
  }

  @Override
  public int hashCode() {
    return getId().hashCode();
  }

}
