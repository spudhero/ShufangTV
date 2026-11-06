#include <jni.h>
#include <mobi.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Each private Java archive owns one handle, used and closed on the reader I/O executor. */
typedef struct { MOBIRawml *raw; MOBIData *data; } Reader;

static void fail(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}

JNIEXPORT jlong JNICALL Java_io_github_spudhero_shufangtv_reader_NativeMobi_open(
        JNIEnv *env, jclass cls, jint descriptor, jlong length) {
    (void) cls;
    if (length < 78 || length > 64 * 1024 * 1024) {
        fail(env, "Kindle 文件不完整或超过 64 MiB 读取上限"); return 0;
    }
    int fd = dup(descriptor);
    if (fd < 0) { fail(env, "无法打开 Kindle 文件描述符"); return 0; }
    FILE *file = fdopen(fd, "rb");
    if (!file) { close(fd); fail(env, "无法读取 Kindle 文件"); return 0; }
    MOBIData *data = mobi_init();
    if (!data) { fclose(file); fail(env, "Kindle 解析内存不足"); return 0; }
    MOBI_RET result = mobi_load_file(data, file);
    fclose(file);
    if (result != MOBI_SUCCESS) {
        mobi_free(data); fail(env, "Kindle 文件头或记录已损坏"); return 0;
    }
    if (mobi_is_encrypted(data)) {
        mobi_free(data); fail(env, "此 Kindle 书籍有 DRM 加密，无法读取"); return 0;
    }
    if (mobi_get_text_maxsize(data) > 32 * 1024 * 1024) {
        mobi_free(data); fail(env, "Kindle 解压正文超过 32 MiB 上限"); return 0;
    }
    MOBIRawml *raw = mobi_init_rawml(data);
    if (!raw) { mobi_free(data); fail(env, "Kindle 解析初始化失败"); return 0; }
    result = mobi_parse_rawml_opt(raw, data, true, false, true);
    /* Image resources borrow the raw record buffers: retain data until rawml is released. */
    if (result != MOBI_SUCCESS) {
        mobi_free_rawml(raw); mobi_free(data); fail(env, "Kindle 正文、目录或资源解析失败"); return 0;
    }
    size_t total = 0, count = 0;
    MOBIPart *lists[] = {raw->markup, raw->flow, raw->resources};
    for (size_t i = 0; i < 3; i++) {
        for (MOBIPart *p = lists[i]; p; p = p->next) {
            if (p->size > 32 * 1024 * 1024 || total > 128 * 1024 * 1024 - p->size || ++count > 16000) {
                mobi_free_rawml(raw); mobi_free(data); fail(env, "Kindle 展开资源超过读取上限"); return 0;
            }
            total += p->size;
        }
    }
    Reader *reader = calloc(1, sizeof(*reader));
    if (!reader) { mobi_free_rawml(raw); mobi_free(data); fail(env, "Kindle 解析内存不足"); return 0; }
    reader->raw = raw;
    reader->data = data;
    return (jlong) (intptr_t) reader;
}

static void part_name(char *name, size_t capacity, const MOBIPart *p, int list) {
    MOBIFileMeta meta = mobi_get_filemeta_by_type(p->type);
    if (p->type == T_OPF) snprintf(name, capacity, "content.opf");
    else snprintf(name, capacity, "%s%05zu.%s", list == 0 ? "part" : list == 1 ? "flow" : "resource", p->uid, meta.extension);
}

JNIEXPORT jobjectArray JNICALL Java_io_github_spudhero_shufangtv_reader_NativeMobi_paths(
        JNIEnv *env, jclass cls, jlong handle) {
    (void) cls;
    Reader *r = (Reader *) (intptr_t) handle;
    if (!r) { fail(env, "Kindle 阅读已关闭"); return NULL; }
    MOBIPart *lists[] = {r->raw->markup, r->raw->flow ? r->raw->flow->next : NULL, r->raw->resources};
    jsize count = 0;
    for (int i = 0; i < 3; i++) for (MOBIPart *p = lists[i]; p; p = p->next) if (p->size) count++;
    jobjectArray out = (*env)->NewObjectArray(env, count, (*env)->FindClass(env, "java/lang/String"), NULL);
    if (!out) return NULL;
    jsize at = 0;
    char name[96];
    for (int i = 0; i < 3; i++) for (MOBIPart *p = lists[i]; p; p = p->next) {
        if (!p->size) continue;
        part_name(name, sizeof(name), p, i);
        jstring value = (*env)->NewStringUTF(env, name);
        if (!value) return NULL;
        (*env)->SetObjectArrayElement(env, out, at++, value);
        (*env)->DeleteLocalRef(env, value);
    }
    return out;
}

JNIEXPORT jbyteArray JNICALL Java_io_github_spudhero_shufangtv_reader_NativeMobi_read(
        JNIEnv *env, jclass cls, jlong handle, jstring path) {
    (void) cls;
    Reader *r = (Reader *) (intptr_t) handle;
    if (!r) { fail(env, "Kindle 阅读已关闭"); return NULL; }
    const char *wanted = (*env)->GetStringUTFChars(env, path, NULL);
    if (!wanted) return NULL;
    MOBIPart *lists[] = {r->raw->markup, r->raw->flow ? r->raw->flow->next : NULL, r->raw->resources};
    MOBIPart *found = NULL;
    char name[96];
    for (int i = 0; i < 3 && !found; i++) for (MOBIPart *p = lists[i]; p; p = p->next) {
        part_name(name, sizeof(name), p, i);
        if (strcmp(wanted, name) == 0) { found = p; break; }
    }
    (*env)->ReleaseStringUTFChars(env, path, wanted);
    if (!found) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, (jsize) found->size);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize) found->size, (const jbyte *) found->data);
    return out;
}

JNIEXPORT void JNICALL Java_io_github_spudhero_shufangtv_reader_NativeMobi_close(
        JNIEnv *env, jclass cls, jlong handle) {
    (void) env; (void) cls;
    Reader *r = (Reader *) (intptr_t) handle;
    if (r) { mobi_free_rawml(r->raw); mobi_free(r->data); free(r); }
}
