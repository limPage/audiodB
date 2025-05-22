#include <jni.h>
#include <android/log.h>
#include "webrtc_vad.h"

#define LOG_TAG "native-vad"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 전역 VAD 인스턴스
static VadInst* vadInst = nullptr;

extern "C" {

// VAD 초기화: 감도 모드 (0~3)
// 0 = Least aggressive, 3 = Most aggressive
JNIEXPORT void JNICALL
Java_com_hjict_audiodb_VadWrapper_init(JNIEnv* env, jobject thiz, jint mode) {
    if (vadInst != nullptr) {
        WebRtcVad_Free(vadInst);
        vadInst = nullptr;
    }

    vadInst = WebRtcVad_Create();
    if (vadInst == nullptr) {
        LOGE("WebRtcVad_Create 실패");
        return;
    }

    if (WebRtcVad_Init(vadInst) != 0) {
        LOGE("WebRtcVad_Init 실패");
        return;
    }

    if (WebRtcVad_set_mode(vadInst, mode) != 0) {
        LOGE("WebRtcVad_set_mode 실패");
    }

    LOGI("WebRTC VAD 초기화 완료. 모드=%d", mode);
}

// PCM 음성 데이터가 사람의 말소리인지 판단
JNIEXPORT jboolean JNICALL
Java_com_hjict_audiodb_VadWrapper_isSpeech(JNIEnv* env, jobject thiz,
                                         jbyteArray audio, jint sampleRate) {
    if (vadInst == nullptr) {
        LOGE("VAD 인스턴스가 초기화되지 않음");
        return JNI_FALSE;
    }

    jsize length = env->GetArrayLength(audio);
    if (length % 2 != 0) {
        LOGE("오디오 길이 에러: 짝수 byte 아님");
        return JNI_FALSE;
    }

    int16_t* pcmData = reinterpret_cast<int16_t*>(env->GetByteArrayElements(audio, nullptr));
    int frameLength = length / 2; // 16-bit PCM

    // WebRTC VAD는 10, 20, 30ms 프레임만 지원
    // 즉, 160, 320, 480 샘플 (16kHz 기준)
    if (!(frameLength == 160 || frameLength == 320 || frameLength == 480)) {
        LOGE("지원하지 않는 프레임 길이: %d 샘플", frameLength);
        env->ReleaseByteArrayElements(audio, reinterpret_cast<jbyte*>(pcmData), 0);
        return JNI_FALSE;
    }

    int result = WebRtcVad_Process(vadInst, sampleRate, pcmData, frameLength);
    env->ReleaseByteArrayElements(audio, reinterpret_cast<jbyte*>(pcmData), 0);
    if (result == 1) {
//        LOGI("▶ 말소리 감지됨");
        return JNI_TRUE;
    } else if (result == 0) {
        return JNI_FALSE;
    } else {
        LOGE("WebRtcVad_Process 오류: %d", result);
        return JNI_FALSE;
    }
}

} // extern "C"
