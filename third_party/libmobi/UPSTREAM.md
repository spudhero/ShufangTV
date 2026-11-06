# libmobi source provenance

Upstream: https://github.com/bfabiszewski/libmobi

Pinned revision: `906274205c11944b628da1c553b255acb1af7c55` (public branch, retrieved 2026-11-06).

The `src` files and `COPYING` are unmodified upstream code, licensed LGPL-3.0-or-later. ShufangTV builds a separate shared `libmobi.so`, with encryption disabled, zlib enabled and the internal XML writer. The application JNI adapter is independently implemented in `app/src/main/cpp/mobi_bridge.c`. See that directory's CMakeLists.txt for the complete source list, definitions and relinking instructions. No Kindle DRM removal is enabled.
