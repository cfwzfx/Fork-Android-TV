#include <jni.h>
#include <android/bitmap.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

// Public, stable libass image ABI. Resolve functions from the bundled libmpv;
// no second copy of libass or its font/FFmpeg dependencies is packaged.
struct ASS_Image {
    int w, h, stride;
    unsigned char *bitmap;
    uint32_t color;
    int dst_x, dst_y;
    ASS_Image *next;
    int type;
};
struct Api {
    void *so = nullptr;
    void *(*library_init)() = nullptr;
    void (*library_done)(void *) = nullptr;
    void (*set_fonts_dir)(void *, const char *) = nullptr;
    void (*set_style_overrides)(void *, char **) = nullptr;
    void *(*renderer_init)(void *) = nullptr;
    void (*renderer_done)(void *) = nullptr;
    void (*set_fonts)(void *, const char *, const char *, int, const char *, int) = nullptr;
    void (*set_frame_size)(void *, int, int) = nullptr;
    void (*set_font_scale)(void *, double) = nullptr;
    void (*set_cache_limits)(void *, int, int) = nullptr;
    void *(*new_track)(void *) = nullptr;
    void (*free_track)(void *) = nullptr;
    void (*process_data)(void *, char *, int) = nullptr;
    void (*process_force_style)(void *) = nullptr;
    ASS_Image *(*render_frame)(void *, void *, long long, int *) = nullptr;
    bool ready = false;
};
static Api api;
static std::once_flag once;
static bool load() {
    std::call_once(once, [] {
        api.so = dlopen("libmpv.so", RTLD_NOW | RTLD_LOCAL);
        if (!api.so) return;
#define ASS_LOAD(name) api.name = reinterpret_cast<decltype(api.name)>(dlsym(api.so, "ass_" #name)); if (!api.name) return
        ASS_LOAD(library_init); ASS_LOAD(library_done); ASS_LOAD(set_fonts_dir);
        ASS_LOAD(set_style_overrides); ASS_LOAD(renderer_init); ASS_LOAD(renderer_done);
        ASS_LOAD(set_fonts); ASS_LOAD(set_frame_size); ASS_LOAD(set_font_scale);
        ASS_LOAD(set_cache_limits); ASS_LOAD(new_track); ASS_LOAD(free_track);
        ASS_LOAD(process_data); ASS_LOAD(process_force_style); ASS_LOAD(render_frame);
#undef ASS_LOAD
        api.ready = true;
    });
    return api.ready;
}
static std::string text(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars); env->ReleaseStringUTFChars(value, chars); return result;
}
struct Session {
    void *library = nullptr, *renderer = nullptr, *track = nullptr;
    int width = 0, height = 0;
    std::string overrides;
    std::vector<std::string> packets;
    size_t bytes = 0;
    bool changed = true;
    ~Session() {
        if (track) api.free_track(track);
        if (renderer) api.renderer_done(renderer);
        if (library) api.library_done(library);
    }
};
#define JNI_ASS(name) Java_com_fongmi_android_tv_player_compat_NativeAss_##name
extern "C" JNIEXPORT jboolean JNICALL JNI_ASS(available)(JNIEnv *, jclass) { return load(); }
extern "C" JNIEXPORT jlong JNICALL JNI_ASS(create)(JNIEnv *env, jclass, jstring config, jstring directory, jstring family) {
    if (!load()) return 0;
    auto *s = new Session();
    s->library = api.library_init();
    if (!s->library) { delete s; return 0; }
    std::string dir = text(env, directory), cfg = text(env, config), font = text(env, family);
    api.set_fonts_dir(s->library, dir.empty() ? nullptr : dir.c_str());
    s->renderer = api.renderer_init(s->library);
    if (!s->renderer) { delete s; return 0; }
    api.set_fonts(s->renderer, nullptr, font.empty() ? "sans-serif" : font.c_str(), 1, cfg.empty() ? nullptr : cfg.c_str(), 1);
    api.set_cache_limits(s->renderer, 4096, 48);
    s->track = api.new_track(s->library);
    if (!s->track) { delete s; return 0; }
    return reinterpret_cast<jlong>(s);
}
extern "C" JNIEXPORT void JNICALL JNI_ASS(destroy)(JNIEnv *, jclass, jlong handle) { delete reinterpret_cast<Session *>(handle); }
extern "C" JNIEXPORT void JNICALL JNI_ASS(feed)(JNIEnv *env, jclass, jlong handle, jbyteArray data) {
    auto *s = reinterpret_cast<Session *>(handle);
    int count = env->GetArrayLength(data);
    if (s->bytes + count > 32 * 1024 * 1024) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "ASS data exceeds 32 MiB"); return;
    }
    std::string packet(count, '\0');
    env->GetByteArrayRegion(data, 0, count, reinterpret_cast<jbyte *>(packet.data()));
    if (env->ExceptionCheck()) return;
    api.process_data(s->track, packet.data(), count);
    api.process_force_style(s->track);
    s->bytes += count; s->packets.push_back(std::move(packet)); s->changed = true;
}
extern "C" JNIEXPORT void JNICALL JNI_ASS(configure)(JNIEnv *env, jclass, jlong handle, jint width, jint height, jdouble scale, jstring overrides) {
    auto *s = reinterpret_cast<Session *>(handle);
    width = std::clamp(width, 1, 1920); height = std::clamp(height, 1, 1080);
    if (s->width != width || s->height != height) s->changed = true;
    s->width = width; s->height = height;
    api.set_frame_size(s->renderer, width, height);
    api.set_font_scale(s->renderer, scale);
    std::string next = text(env, overrides);
    if (next != s->overrides) {
        s->overrides = next;
        std::vector<std::string> values;
        size_t start = 0, end;
        while ((end = next.find('\n', start)) != std::string::npos) {
            values.push_back(next.substr(start, end - start)); start = end + 1;
        }
        if (start < next.size()) values.push_back(next.substr(start));
        std::vector<char *> ptrs;
        for (auto &v : values) ptrs.push_back(v.data());
        ptrs.push_back(nullptr); api.set_style_overrides(s->library, ptrs.data());
        void *track = api.new_track(s->library);
        if (!track) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "libass track allocation failed"); return;
        }
        api.free_track(s->track); s->track = track;
        for (auto &packet : s->packets) api.process_data(s->track, packet.data(), static_cast<int>(packet.size()));
        api.process_force_style(s->track); s->changed = true;
    }
}
extern "C" JNIEXPORT jobject JNICALL JNI_ASS(render)(JNIEnv *env, jclass, jlong handle, jlong time) {
    auto *s = reinterpret_cast<Session *>(handle);
    int changed = 0;
    ASS_Image *images = api.render_frame(s->renderer, s->track, time, &changed);
    if (!changed && !s->changed) return nullptr;
    s->changed = false;
    int left = s->width, top = s->height, right = 0, bottom = 0;
    for (auto *im = images; im; im = im->next) {
        if (im->w <= 0 || im->h <= 0 || !im->bitmap) continue;
        left = std::min(left, std::clamp(im->dst_x, 0, s->width));
        top = std::min(top, std::clamp(im->dst_y, 0, s->height));
        right = std::max(right, static_cast<int>(std::clamp<int64_t>(int64_t(im->dst_x) + im->w, 0, s->width)));
        bottom = std::max(bottom, static_cast<int>(std::clamp<int64_t>(int64_t(im->dst_y) + im->h, 0, s->height)));
    }
    jobject bitmap = nullptr;
    if (right > left && bottom > top) {
        jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
        jobject config = env->GetStaticObjectField(configClass, env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"));
        jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        bitmap = env->CallStaticObjectMethod(bitmapClass, env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"), right - left, bottom - top, config);
        if (env->ExceptionCheck() || !bitmap) return nullptr;
        AndroidBitmapInfo info{}; void *pixels = nullptr;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
        for (auto *im = images; im; im = im->next) {
            if (!im->bitmap || im->stride < im->w || im->w <= 0 || im->h <= 0) continue;
            int x0 = std::max(left, im->dst_x), y0 = std::max(top, im->dst_y);
            int x1 = static_cast<int>(std::min<int64_t>(right, int64_t(im->dst_x) + im->w));
            int y1 = static_cast<int>(std::min<int64_t>(bottom, int64_t(im->dst_y) + im->h));
            uint32_t rgb[3] = {im->color >> 24, (im->color >> 16) & 255, (im->color >> 8) & 255};
            for (int y = y0; y < y1; y++) {
                auto *row = reinterpret_cast<uint8_t *>(pixels) + (y - top) * info.stride;
                for (int x = x0; x < x1; x++) {
                    uint32_t alpha = ((255 - (im->color & 255)) * im->bitmap[(y - im->dst_y) * im->stride + x - im->dst_x] + 127) / 255;
                    auto *dst = row + (x - left) * 4;
                    for (int c = 0; c < 3; c++) dst[c] = (rgb[c] * alpha + dst[c] * (255 - alpha) + 127) / 255;
                    dst[3] = alpha + (dst[3] * (255 - alpha) + 127) / 255;
                }
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    jclass cls = env->FindClass("com/fongmi/android/tv/player/compat/NativeAss$Frame");
    return env->NewObject(cls, env->GetMethodID(cls, "<init>", "(Landroid/graphics/Bitmap;IIII)V"), bitmap, left, top, s->width, s->height);
}
