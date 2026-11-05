package io.github.spudhero.shufangtv.data;

/** Human-readable UTF-8 export, independent of remote book files and services. */
public final class NoteExport {
  private NoteExport() {}

  public static String render(LibraryStore library, boolean markdown) {
    StringBuilder out = new StringBuilder(markdown ? "# 书房 TV · 书签与笔记\n\n" : "书房 TV · 书签与笔记\n\n");
    for (BookRecord book : library.books) {
      boolean header = false;
      for (LibraryStore.Mark mark : library.marks) {
        if (!book.id.equals(mark.bookId)) continue;
        if (!header) {
          out.append(markdown ? "## " : "《")
              .append(markdown ? escape(book.title) : book.title)
              .append(markdown ? "\n\n" : "》\n\n");
          header = true;
        }
        out.append(mark.isNote() ? "段落收藏" : "位置书签").append(" · ");
        if (book.format.equals("pdf")) out.append("第 ").append(mark.pdfPage + 1).append(" 页");
        else if (book.format.equals("txt"))
          out.append("TXT 片段 ").append(mark.txtByte).append("，字位 ").append(mark.offset);
        else out.append("第 ").append(mark.chapter + 1).append(" 章，字位 ").append(mark.offset);
        out.append("\n\n");
        for (String line : mark.excerpt.split("\n", -1))
          out.append(markdown ? "> " : "  ").append(markdown ? escape(line) : line).append('\n');
        if (!mark.note.isEmpty())
          out.append("\n备注：").append(markdown ? escape(mark.note) : mark.note).append('\n');
        out.append('\n');
      }
    }
    return out.toString();
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("#", "\\#");
  }
}
