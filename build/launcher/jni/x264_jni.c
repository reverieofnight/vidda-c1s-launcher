/*
 * Dream 桌面 JNI 胶水层（x264 4K 编码）
 * Copyright (C) 2026 Dream 桌面项目贡献者
 *
 * 本程序是自由软件：你可以依据 GNU 通用公共许可证第 2 版
 * 或（按你的选择）任何更新版本重新发布和/或修改它。
 *
 * 本程序按“无担保”发布；详见仓库根目录 LICENSE。
 */
#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "x264.h"

#define TAG "DSH_X264"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static const uint8_t *strip_start_code(const uint8_t *p, int len, int *out_len) {
    if (len >= 4 && p[0] == 0 && p[1] == 0 && p[2] == 0 && p[3] == 1) {
        *out_len = len - 4;
        return p + 4;
    }
    if (len >= 3 && p[0] == 0 && p[1] == 0 && p[2] == 1) {
        *out_len = len - 3;
        return p + 3;
    }
    *out_len = len;
    return p;
}

static void put_be32(uint8_t *p, uint32_t v) {
    p[0] = (v >> 24) & 0xFF;
    p[1] = (v >> 16) & 0xFF;
    p[2] = (v >> 8) & 0xFF;
    p[3] = v & 0xFF;
}

static void fill_i420(x264_picture_t *pic, int w, int h, int frame) {
    uint8_t *y = pic->img.plane[0];
    uint8_t *u = pic->img.plane[1];
    uint8_t *v = pic->img.plane[2];
    int ys = pic->img.i_stride[0];
    int us = pic->img.i_stride[1];
    int vs = pic->img.i_stride[2];

    for (int j = 0; j < h; j++) {
        for (int i = 0; i < w; i++) {
            int val = 32 + ((i * 180 / w) + (j * 120 / h) + frame * 8) % 200;
            if (i > w / 3 && i < 2 * w / 3 && j > h / 3 && j < 2 * h / 3) {
                val = 235;
            }
            y[j * ys + i] = (uint8_t)val;
        }
    }
    for (int j = 0; j < h / 2; j++) {
        for (int i = 0; i < w / 2; i++) {
            u[j * us + i] = (uint8_t)(110 + (i * 40 / (w / 2)));
            v[j * vs + i] = (uint8_t)(120 + (j * 40 / (h / 2)));
        }
    }
}

JNIEXPORT jbyteArray JNICALL
Java_tv_dsh_launcher_X264Encode4KActivity_encode4K(
        JNIEnv *env, jclass clazz, jint width, jint height, jint frameCount) {
    int64_t t0 = now_ms();
    x264_param_t param;
    x264_t *enc = NULL;
    x264_picture_t pic;
    x264_picture_t picOut;
    int picInited = 0;

    if (width <= 0 || height <= 0 || frameCount <= 0) {
        return NULL;
    }

    x264_param_default_preset(&param, "ultrafast", "zerolatency");
    param.i_width = width;
    param.i_height = height;
    param.i_fps_num = 1;
    param.i_fps_den = 1;
    param.i_threads = 4;
    param.b_annexb = 1;
    param.b_repeat_headers = 1;
    param.i_keyint_max = 1;
    param.i_keyint_min = 1;
    param.rc.i_rc_method = X264_RC_CRF;
    param.rc.f_rf_constant = 18.0f;
    param.i_log_level = X264_LOG_WARNING;
    x264_param_apply_profile(&param, "high");

    enc = x264_encoder_open(&param);
    if (!enc) {
        LOGE("x264_encoder_open failed");
        return NULL;
    }
    int64_t tOpen = now_ms();
    LOGI("encoder open %dx%d frames=%d cost=%lldms", width, height, frameCount,
         (long long)(tOpen - t0));

    x264_nal_t *headers = NULL;
    int headerCount = 0;
    if (x264_encoder_headers(enc, &headers, &headerCount) < 0) {
        LOGE("x264_encoder_headers failed");
        x264_encoder_close(enc);
        return NULL;
    }
    const uint8_t *sps = NULL, *pps = NULL;
    int spsLen = 0, ppsLen = 0;
    for (int i = 0; i < headerCount; i++) {
        int rawLen = 0;
        const uint8_t *raw = strip_start_code(headers[i].p_payload, headers[i].i_payload, &rawLen);
        if (headers[i].i_type == NAL_SPS) {
            sps = raw; spsLen = rawLen;
        } else if (headers[i].i_type == NAL_PPS) {
            pps = raw; ppsLen = rawLen;
        }
    }
    if (!sps || !pps || spsLen <= 0 || ppsLen <= 0) {
        LOGE("missing SPS/PPS sps=%d pps=%d", spsLen, ppsLen);
        x264_encoder_close(enc);
        return NULL;
    }
    int64_t tHeaders = now_ms();
    LOGI("headers sps=%d pps=%d cost=%lldms", spsLen, ppsLen,
         (long long)(tHeaders - tOpen));

    memset(&picOut, 0, sizeof(picOut));
    if (x264_picture_alloc(&pic, X264_CSP_I420, width, height) < 0) {
        LOGE("x264_picture_alloc failed");
        x264_encoder_close(enc);
        return NULL;
    }
    picInited = 1;

    // 预留：SPS + PPS + frameCount + 每帧长度/数据
    size_t cap = 4 + spsLen + 4 + ppsLen + 4 + 16 * 1024 * 1024;
    uint8_t *out = (uint8_t *) malloc(cap);
    if (!out) {
        LOGE("out malloc failed");
        x264_picture_clean(&pic);
        x264_encoder_close(enc);
        return NULL;
    }
    size_t pos = 0;
    put_be32(out + pos, spsLen); pos += 4;
    memcpy(out + pos, sps, spsLen); pos += spsLen;
    put_be32(out + pos, ppsLen); pos += 4;
    memcpy(out + pos, pps, ppsLen); pos += ppsLen;
    put_be32(out + pos, frameCount); pos += 4;

    int64_t tEncode = now_ms();
    for (int f = 0; f < frameCount; f++) {
        fill_i420(&pic, width, height, f);
        pic.i_pts = f;
        x264_nal_t *nals = NULL;
        int nalCount = 0;
        int got = x264_encoder_encode(enc, &nals, &nalCount, &pic, &picOut);
        if (got < 0) {
            LOGE("x264_encoder_encode failed frame=%d", f);
            free(out);
            x264_picture_clean(&pic);
            x264_encoder_close(enc);
            return NULL;
        }
        size_t frameStart = pos;
        pos += 4; // 先占位
        size_t payload = 0;
        for (int i = 0; i < nalCount; i++) {
            if (nals[i].i_type == NAL_SPS || nals[i].i_type == NAL_PPS
                    || nals[i].i_type == NAL_SEI || nals[i].i_type == NAL_AUD) {
                continue;
            }
            int rawLen = 0;
            const uint8_t *raw = strip_start_code(nals[i].p_payload, nals[i].i_payload, &rawLen);
            if (rawLen <= 0) continue;
            if (pos + 4 + rawLen > cap) {
                LOGE("output overflow");
                free(out);
                x264_picture_clean(&pic);
                x264_encoder_close(enc);
                return NULL;
            }
            put_be32(out + pos, rawLen); pos += 4;
            memcpy(out + pos, raw, rawLen); pos += rawLen;
            payload += 4 + rawLen;
        }
        put_be32(out + frameStart, (uint32_t)payload);
        LOGI("encoded frame=%d size=%d got=%d", f, (int)payload, got);
    }

    int64_t tDone = now_ms();
    LOGI("encode done frames=%d cost=%lldms total=%lldms",
         frameCount, (long long)(tDone - tEncode), (long long)(tDone - t0));

    x264_picture_clean(&pic);
    x264_encoder_close(enc);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)pos);
    if (ret) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)pos, (const jbyte *)out);
    }
    free(out);
    return ret;
}

JNIEXPORT jbyteArray JNICALL
Java_cn_rainynight_dream_LauncherActivity_encodeI4204K(
        JNIEnv *env, jclass clazz, jbyteArray yuvArray,
        jint width, jint height, jint frameCount) {
    if (!yuvArray || width <= 0 || height <= 0 || frameCount <= 0) {
        return NULL;
    }
    int ySize = width * height;
    int uvSize = (width / 2) * (height / 2);
    int need = ySize + uvSize * 2;
    if ((*env)->GetArrayLength(env, yuvArray) < need) {
        LOGE("yuv array too small: need=%d", need);
        return NULL;
    }
    jbyte *yuv = (*env)->GetByteArrayElements(env, yuvArray, NULL);
    if (!yuv) {
        return NULL;
    }

    int64_t t0 = now_ms();
    x264_param_t param;
    x264_t *enc = NULL;
    x264_picture_t pic;
    x264_picture_t picOut;
    memset(&pic, 0, sizeof(pic));
    memset(&picOut, 0, sizeof(picOut));

    x264_param_default_preset(&param, "ultrafast", "zerolatency");
    param.i_width = width;
    param.i_height = height;
    param.i_fps_num = 1;
    param.i_fps_den = 1;
    param.i_threads = 4;
    param.b_annexb = 1;
    param.b_repeat_headers = 1;
    param.i_keyint_max = 1;
    param.i_keyint_min = 1;
    param.rc.i_rc_method = X264_RC_CRF;
    param.rc.f_rf_constant = 18.0f;
    param.i_log_level = X264_LOG_WARNING;
    x264_param_apply_profile(&param, "high");
    // 显式声明 BT.709 limited range，避免解码/显示按错矩阵解释
    param.vui.i_colorprim = 1;
    param.vui.i_transfer = 1;
    param.vui.i_colmatrix = 1;
    param.vui.i_chroma_loc = 0;
    param.vui.b_fullrange = 0;

    enc = x264_encoder_open(&param);
    if (!enc) {
        LOGE("encodeI4204K open failed");
        (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);
        return NULL;
    }
    int64_t tOpen = now_ms();

    x264_nal_t *headers = NULL;
    int headerCount = 0;
    if (x264_encoder_headers(enc, &headers, &headerCount) < 0) {
        LOGE("encodeI4204K headers failed");
        x264_encoder_close(enc);
        (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);
        return NULL;
    }
    const uint8_t *sps = NULL, *pps = NULL;
    int spsLen = 0, ppsLen = 0;
    for (int i = 0; i < headerCount; i++) {
        int rawLen = 0;
        const uint8_t *raw = strip_start_code(headers[i].p_payload, headers[i].i_payload, &rawLen);
        if (headers[i].i_type == NAL_SPS) {
            sps = raw; spsLen = rawLen;
        } else if (headers[i].i_type == NAL_PPS) {
            pps = raw; ppsLen = rawLen;
        }
    }
    if (!sps || !pps || spsLen <= 0 || ppsLen <= 0) {
        LOGE("encodeI4204K missing SPS/PPS");
        x264_encoder_close(enc);
        (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);
        return NULL;
    }

    if (x264_picture_alloc(&pic, X264_CSP_I420, width, height) < 0) {
        LOGE("encodeI4204K picture_alloc failed");
        x264_encoder_close(enc);
        (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);
        return NULL;
    }

    size_t cap = 4 + spsLen + 4 + ppsLen + 4 + 16 * 1024 * 1024;
    uint8_t *out = (uint8_t *) malloc(cap);
    if (!out) {
        x264_picture_clean(&pic);
        x264_encoder_close(enc);
        (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);
        return NULL;
    }
    size_t pos = 0;
    put_be32(out + pos, spsLen); pos += 4;
    memcpy(out + pos, sps, spsLen); pos += spsLen;
    put_be32(out + pos, ppsLen); pos += 4;
    memcpy(out + pos, pps, ppsLen); pos += ppsLen;
    put_be32(out + pos, frameCount); pos += 4;

    int64_t tEncode = now_ms();
    for (int f = 0; f < frameCount; f++) {
        memcpy(pic.img.plane[0], yuv, ySize);
        memcpy(pic.img.plane[1], yuv + ySize, uvSize);
        memcpy(pic.img.plane[2], yuv + ySize + uvSize, uvSize);
        pic.i_pts = f;

        x264_nal_t *nals = NULL;
        int nalCount = 0;
        int got = x264_encoder_encode(enc, &nals, &nalCount, &pic, &picOut);
        if (got < 0) {
            LOGE("encodeI4204K encode failed frame=%d", f);
            free(out);
            x264_picture_clean(&pic);
            x264_encoder_close(enc);
            (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);
            return NULL;
        }
        size_t frameStart = pos;
        pos += 4;
        size_t payload = 0;
        for (int i = 0; i < nalCount; i++) {
            if (nals[i].i_type == NAL_SPS || nals[i].i_type == NAL_PPS
                    || nals[i].i_type == NAL_SEI || nals[i].i_type == NAL_AUD) {
                continue;
            }
            int rawLen = 0;
            const uint8_t *raw = strip_start_code(nals[i].p_payload, nals[i].i_payload, &rawLen);
            if (rawLen <= 0) continue;
            if (pos + 4 + rawLen > cap) {
                free(out);
                x264_picture_clean(&pic);
                x264_encoder_close(enc);
                (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);
                return NULL;
            }
            put_be32(out + pos, rawLen); pos += 4;
            memcpy(out + pos, raw, rawLen); pos += rawLen;
            payload += 4 + rawLen;
        }
        put_be32(out + frameStart, (uint32_t)payload);
        LOGI("image encoded frame=%d size=%d got=%d", f, (int)payload, got);
    }

    int64_t tDone = now_ms();
    LOGI("image encode done %dx%d frames=%d open=%lldms encode=%lldms total=%lldms",
         width, height, frameCount, (long long)(tOpen - t0),
         (long long)(tDone - tEncode), (long long)(tDone - t0));

    x264_picture_clean(&pic);
    x264_encoder_close(enc);
    (*env)->ReleaseByteArrayElements(env, yuvArray, yuv, JNI_ABORT);

    jbyteArray ret = (*env)->NewByteArray(env, (jsize)pos);
    if (ret) {
        (*env)->SetByteArrayRegion(env, ret, 0, (jsize)pos, (const jbyte *)out);
    }
    free(out);
    return ret;
}
