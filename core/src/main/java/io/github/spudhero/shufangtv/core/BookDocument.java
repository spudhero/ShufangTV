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
    public final boolean hasImages;
    public final List<Illustration> images;

    public Chapter(String title, String text) {
      this(title, text, false);
    }

    public Chapter(String title, String text, boolean hasImages) {
      this(title, text, Collections.emptyList(), hasImages);
    }

    public Chapter(String title, String text, List<Illustration> images) {
      this(title, text, images, !images.isEmpty());
    }

    private Chapter(String title, String text, List<Illustration> images, boolean hasImages) {
      this.title = Objects.requireNonNull(title, "title");
      this.text = Objects.requireNonNull(text, "text");
      this.hasImages = hasImages;
      this.images = Collections.unmodifiableList(new ArrayList<>(images));
    }
  }

  /** Image ranges share the same UTF-16 anchor space as text, bookmarks and search. */
  public static final class Illustration {
    public final int start, end;
    public final String source, alt, svg;

    public Illustration(int start, int end, String source, String alt, String svg) {
      this.start = start;
      this.end = end;
      this.source = Objects.requireNonNull(source);
      this.alt = Objects.requireNonNull(alt);
      this.svg = Objects.requireNonNull(svg);
    }
  }
}
