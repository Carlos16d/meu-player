#include <jni.h>
#include <string>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <libtorrent/session.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/file_storage.hpp>
#include <libtorrent/torrent_info.hpp>
#include <android/log.h>

#define TAG "TorrentBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace lt = libtorrent;

lt::session* g_session = nullptr;
lt::torrent_handle g_torrent;
std::string g_save_path;
JavaVM* g_jvm = nullptr;
jobject g_callback_obj = nullptr;
jmethodID g_progress_method = nullptr;
jmethodID g_ready_method = nullptr;
jmethodID g_error_method = nullptr;
jmethodID g_status_method = nullptr;
bool g_running = true;

extern "C" JNIEXPORT void JNICALL
Java_com_seunome_meuapp_TorrentBridge_initEngine(
    JNIEnv* env, jobject thiz, jstring save_path) {
    
    const char* path = env->GetStringUTFChars(save_path, nullptr);
    g_save_path = std::string(path);
    env->ReleaseStringUTFChars(save_path, path);
    
    // Salva referência global da JVM e do callback
    env->GetJavaVM(&g_jvm);
    g_callback_obj = env->NewGlobalRef(thiz);
    
    jclass clazz = env->GetObjectClass(thiz);
    g_progress_method = env->GetMethodID(clazz, "onProgress", "(FII)V");
    g_ready_method = env->GetMethodID(clazz, "onReady", "(Ljava/lang/String;)V");
    g_error_method = env->GetMethodID(clazz, "onError", "(Ljava/lang/String;)V");
    g_status_method = env->GetMethodID(clazz, "onStatus", "(Ljava/lang/String;)V");
    
    // Configura a sessão
    lt::settings_pack pack;
    pack.set_int(lt::settings_pack::alert_mask,
        lt::alert::error_notification |
        lt::alert::storage_notification |
        lt::alert::status_notification |
        lt::alert::progress_notification);
    pack.set_bool(lt::settings_pack::enable_dht, true);
    pack.set_bool(lt::settings_pack::enable_lsd, true);
    pack.set_bool(lt::settings_pack::enable_upnp, true);
    pack.set_bool(lt::settings_pack::enable_natpmp, true);
    pack.set_int(lt::settings_pack::download_rate_limit, 0);
    pack.set_int(lt::settings_pack::upload_rate_limit, 0);
    pack.set_int(lt::settings_pack::connections_limit, 200);
    pack.set_bool(lt::settings_pack::announce_to_all_trackers, true);
    pack.set_bool(lt::settings_pack::announce_to_all_tiers, true);
    
    g_session = new lt::session(pack);
    
    LOGD("Torrent engine initialized");
}

extern "C" JNIEXPORT void JNICALL
Java_com_seunome_meuapp_TorrentBridge_addMagnet(
    JNIEnv* env, jobject thiz, jstring magnet_uri) {
    
    const char* uri = env->GetStringUTFChars(magnet_uri, nullptr);
    
    lt::add_torrent_params params;
    lt::error_code ec;
    lt::parse_magnet_uri(std::string(uri), params, ec);
    
    if (ec) {
        LOGE("Error parsing magnet: %s", ec.message().c_str());
        env->ReleaseStringUTFChars(magnet_uri, uri);
        return;
    }
    
    params.save_path = g_save_path;
    params.flags |= lt::torrent_flags::auto_managed;
    params.flags |= lt::torrent_flags::sequential_download;
    
    g_torrent = g_session->add_torrent(params);
    
    env->ReleaseStringUTFChars(magnet_uri, uri);
    
    // Thread para processar alertas
    std::thread([env, thiz]() {
        JNIEnv* threadEnv;
        g_jvm->AttachCurrentThread(&threadEnv, nullptr);
        
        while (g_running && g_session) {
            std::vector<lt::alert*> alerts;
            g_session->pop_alerts(&alerts);
            
            for (auto alert : alerts) {
                switch (alert->type()) {
                    case lt::torrent_added_alert::alert_type: {
                        auto* a = lt::alert_cast<lt::torrent_added_alert>(alert);
                        if (a && g_status_method) {
                            threadEnv->CallVoidMethod(g_callback_obj, g_status_method,
                                threadEnv->NewStringUTF(("Torrent adicionado: " + a->torrent_name()).c_str()));
                        }
                        break;
                    }
                    
                    case lt::state_changed_alert::alert_type: {
                        auto* a = lt::alert_cast<lt::state_changed_alert>(alert);
                        if (a && g_torrent.is_valid()) {
                            lt::torrent_status status = g_torrent.status();
                            if (g_progress_method) {
                                threadEnv->CallVoidMethod(g_callback_obj, g_progress_method,
                                    status.progress * 100.0f,
                                    status.download_rate / 1024,
                                    status.num_peers);
                            }
                        }
                        break;
                    }
                    
                    case lt::torrent_finished_alert::alert_type: {
                        auto* a = lt::alert_cast<lt::torrent_finished_alert>(alert);
                        if (a && g_ready_method && g_torrent.is_valid()) {
                            lt::torrent_info info = *g_torrent.torrent_file();
                            lt::file_storage files = info.files();
                            
                            // Procura o maior arquivo de vídeo
                            int best_idx = -1;
                            long long best_size = 0;
                            for (int i = 0; i < files.num_files(); i++) {
                                std::string name = files.file_name(i);
                                long long size = files.file_size(i);
                                if ((name.find(".mp4") != std::string::npos ||
                                     name.find(".mkv") != std::string::npos ||
                                     name.find(".avi") != std::string::npos ||
                                     name.find(".webm") != std::string::npos) &&
                                    size > best_size) {
                                    best_size = size;
                                    best_idx = i;
                                }
                            }
                            
                            if (best_idx >= 0) {
                                std::string video_path = g_save_path + "/" + files.file_path(best_idx);
                                threadEnv->CallVoidMethod(g_callback_obj, g_ready_method,
                                    threadEnv->NewStringUTF(video_path.c_str()));
                            }
                        }
                        break;
                    }
                    
                    case lt::torrent_error_alert::alert_type: {
                        auto* a = lt::alert_cast<lt::torrent_error_alert>(alert);
                        if (a && g_error_method) {
                            threadEnv->CallVoidMethod(g_callback_obj, g_error_method,
                                threadEnv->NewStringUTF(a->message().c_str()));
                        }
                        break;
                    }
                }
            }
            
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
        
        g_jvm->DetachCurrentThread();
    }).detach();
}

extern "C" JNIEXPORT void JNICALL
Java_com_seunome_meuapp_TorrentBridge_pause(
    JNIEnv* env, jobject thiz) {
    if (g_torrent.is_valid()) {
        g_torrent.pause();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_seunome_meuapp_TorrentBridge_resume(
    JNIEnv* env, jobject thiz) {
    if (g_torrent.is_valid()) {
        g_torrent.resume();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_seunome_meuapp_TorrentBridge_destroy(
    JNIEnv* env, jobject thiz) {
    g_running = false;
    if (g_torrent.is_valid()) {
        g_torrent.pause();
    }
    if (g_session) {
        delete g_session;
        g_session = nullptr;
    }
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
}
