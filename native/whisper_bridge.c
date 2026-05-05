#include "whisper.h"
#include <string.h>

int whisper_bridge_transcribe(
    struct whisper_context *ctx,
    const float *samples,
    int n_samples,
    int n_threads,
    const char *language
) {
    struct whisper_full_params *params = whisper_full_default_params_by_ref(WHISPER_SAMPLING_GREEDY);
    if (!params) return -1;

    if (n_threads > 0) params->n_threads = n_threads;
    if (language && language[0] && strcmp(language, "auto") != 0) {
        params->language = language;
    }
    params->no_timestamps = false;
    params->print_special = false;
    params->print_progress = false;
    params->print_realtime = false;

    int ret = whisper_full(ctx, *params, samples, n_samples);
    whisper_free_params(params);
    return ret;
}
