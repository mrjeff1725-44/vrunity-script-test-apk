#include <android/native_activity.h>
#include <android/native_window.h>
#include <android/input.h>
#include <android/looper.h>
#include <android/keycodes.h>
#include <jni.h>
#include <stdatomic.h>

static _Atomic int resumed = 0;
static _Atomic int windowReady = 0;
static _Atomic int destroyed = 0;

static AInputQueue *inputQueue = NULL;
static ANativeActivity *inputHost = NULL;

static int drainInput(int fd, int events, void *data) {
    AInputQueue *queue = (AInputQueue *)data;
    AInputEvent *event = NULL;
    while (AInputQueue_getEvent(queue, &event) >= 0) {
        if (AInputQueue_preDispatchEvent(queue, event)) continue;
        int handled = AInputEvent_getType(event) == AINPUT_EVENT_TYPE_MOTION;
        if (AInputEvent_getType(event) == AINPUT_EVENT_TYPE_KEY && AKeyEvent_getKeyCode(event) == AKEYCODE_BACK) {
            handled = 1;
            if (AKeyEvent_getAction(event) == AKEY_EVENT_ACTION_UP && inputHost) ANativeActivity_finish(inputHost);
        }
        AInputQueue_finishEvent(queue, event, handled);
    }
    return 1;
}

static void onInputQueueCreated(ANativeActivity *activity, AInputQueue *queue) {
    inputHost = activity;
    inputQueue = queue;
    ALooper *looper = ALooper_forThread();
    if (looper) AInputQueue_attachLooper(queue, looper, ALOOPER_POLL_CALLBACK, drainInput, queue);
}

static void onInputQueueDestroyed(ANativeActivity *activity, AInputQueue *queue) {
    AInputQueue_detachLooper(queue);
    if (inputQueue == queue) inputQueue = NULL;
}


static void onResume(ANativeActivity *activity) { atomic_store(&resumed, 1); }
static void onPause(ANativeActivity *activity) { atomic_store(&resumed, 0); }
static void onWindow(ANativeActivity *activity, ANativeWindow *window) {
    atomic_store(&windowReady, window && ANativeWindow_getWidth(window) > 0 && ANativeWindow_getHeight(window) > 0);
}
static void onWindowDestroyed(ANativeActivity *activity, ANativeWindow *window) {
    atomic_store(&windowReady, 0);
}
static void onDestroy(ANativeActivity *activity) {
    if (inputQueue) onInputQueueDestroyed(activity, inputQueue);
    inputHost = NULL;
    atomic_store(&destroyed, 1);
    atomic_store(&resumed, 0);
    atomic_store(&windowReady, 0);
}

JNIEXPORT void ANativeActivity_onCreate(ANativeActivity *activity, void *savedState, size_t savedStateSize) {
    atomic_store(&destroyed, 0);
    atomic_store(&resumed, 0);
    atomic_store(&windowReady, 0);
    activity->callbacks->onResume = onResume;
    activity->callbacks->onPause = onPause;
    activity->callbacks->onDestroy = onDestroy;
    activity->callbacks->onInputQueueCreated = onInputQueueCreated;
    activity->callbacks->onInputQueueDestroyed = onInputQueueDestroyed;
    activity->callbacks->onNativeWindowCreated = onWindow;
    activity->callbacks->onNativeWindowResized = onWindow;
    activity->callbacks->onNativeWindowDestroyed = onWindowDestroyed;
}

JNIEXPORT jboolean JNICALL Java_com_vrunity_vrapk_Xr_nativeWindowReady(JNIEnv *env, jobject thiz) {
    return atomic_load(&resumed) && atomic_load(&windowReady) && !atomic_load(&destroyed) ? JNI_TRUE : JNI_FALSE;
}
