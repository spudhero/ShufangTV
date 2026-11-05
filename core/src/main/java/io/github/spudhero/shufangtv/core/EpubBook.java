package io.github.spudhero.shufangtv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Metadata and reading order only; inspecting an EPUB never loads its chapter bodies. */
public final class EpubBook {
  public final String title;
  public final String author;
  public final List<SpineItem> spine;
  public final String coverPath;

  public EpubBook(String title, String author, List<SpineItem> spine, String coverPath) {
    this.title = Objects.requireNonNull(title, "title");
    this.author = Objects.requireNonNull(author, "author");
    this.spine = Collections.unmodifiableList(new ArrayList<>(spine));
    for (SpineItem item : this.spine) Objects.requireNonNull(item, "spine item");
    this.coverPath = coverPath;
  }

  public static final class SpineItem {
    public final String title;
    public final String path;
    final boolean navigationTitle;

    public SpineItem(String title, String path) {
      this(title, path, true);
    }

    SpineItem(String title, String path, boolean navigationTitle) {
      this.title = Objects.requireNonNull(title, "title");
      this.path = Objects.requireNonNull(path, "path");
      this.navigationTitle = navigationTitle;
    }
  }
}
