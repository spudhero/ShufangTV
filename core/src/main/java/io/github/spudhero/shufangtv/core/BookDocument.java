package io.github.spudhero.shufangtv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A fully imported text book. Positions within chapter text use UTF-16 offsets. */
public final class BookDocument {
  public final String title;
  public final String author;
  public final List<Chapter> chapters;
  public final byte[] coverBytes;

  public BookDocument(String title, String author, List<Chapter> chapters, byte[] coverBytes) {
    this.title = Objects.requireNonNull(title, "title");
    this.author = Objects.requireNonNull(author, "author");
    this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
    for (Chapter chapter : this.chapters) Objects.requireNonNull(chapter, "chapter");
    this.coverBytes = coverBytes == null ? null : coverBytes.clone();
  }

  public static final class Chapter {
    public final String title;
    public final String text;

    public Chapter(String title, String text) {
      this.title = Objects.requireNonNull(title, "title");
      this.text = Objects.requireNonNull(text, "text");
    }
  }
}
