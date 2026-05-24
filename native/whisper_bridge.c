#include "whisper.h"
#include <string.h>

int whisper_bridge_transcribe(
    struct whisper_context *ctx,
    const float *samples,
    int n_samples,
    int n_threads,
    const char *language,
    const char *initial_prompt
) {
    struct whisper_full_params *params = whisper_full_default_params_by_ref(WHISPER_SAMPLING_GREEDY);
    if (!params) return -1;

    if (n_threads > 0) params->n_threads = n_threads;
    if (language && language[0]) {
        params->language = language;
    }
    if (initial_prompt && initial_prompt[0]) {
        params->initial_prompt = initial_prompt;
        params->carry_initial_prompt = true;
    }

    // Keep Whisper output as natural ASR segments. Subtitle-length splitting is
    // handled later in Java after translation; doing it here cuts source phrases
    // mid-term and gives the translator less context.
    params->no_timestamps = false;
    params->token_timestamps = false;
    params->max_len = 0;
    params->split_on_word = false;
    params->print_special = false;
    params->print_progress = false;
    params->print_realtime = false;

    int ret = whisper_full(ctx, *params, samples, n_samples);
    whisper_free_params(params);
    return ret;
}
