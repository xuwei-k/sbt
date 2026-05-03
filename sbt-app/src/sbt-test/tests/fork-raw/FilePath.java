package sbt.internal.worker1;

import java.net.URI;

// overwrite
// https://github.com/sbt/sbt/blob/4ec3a753e7c9fce3775e/worker/src/main/java/sbt/internal/worker1/FilePath.java
public class FilePath {
  public URI path;
  public String digest;

  public FilePath(URI path, String digest) {
    this.path = path;
    this.digest = digest;
  }

  public static String newMethod() {
    return "a";
  }
}
