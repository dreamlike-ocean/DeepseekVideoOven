#include "llama.h"

#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

struct video_oven_hunyuan_ctx {
    llama_model *model;
    llama_context *ctx;
    const llama_vocab *vocab;
};

static std::once_flag backend_once;

static char *copy_result(const std::string &text) {
    char *out = static_cast<char *>(std::malloc(text.size() + 1));
    if (!out) {
        return nullptr;
    }
    std::memcpy(out, text.c_str(), text.size() + 1);
    return out;
}

static std::string fallback_prompt(const char *system_prompt, const char *user_content) {
    std::string prompt;
    if (system_prompt && system_prompt[0]) {
        prompt += "System:\n";
        prompt += system_prompt;
        prompt += "\n\n";
    }
    prompt += "User:\n";
    prompt += user_content ? user_content : "";
    prompt += "\n\nAssistant:\n";
    return prompt;
}

static std::string build_prompt(video_oven_hunyuan_ctx *state, const char *system_prompt, const char *user_content) {
    llama_chat_message messages[2] = {
            {"system", system_prompt ? system_prompt : ""},
            {"user", user_content ? user_content : ""}
    };
    const char *tmpl = llama_model_chat_template(state->model, nullptr);
    int32_t needed = llama_chat_apply_template(tmpl, messages, 2, true, nullptr, 0);
    if (needed <= 0) {
        return fallback_prompt(system_prompt, user_content);
    }
    std::string prompt(static_cast<size_t>(needed), '\0');
    int32_t written = llama_chat_apply_template(tmpl, messages, 2, true, &prompt[0], needed);
    if (written <= 0) {
        return fallback_prompt(system_prompt, user_content);
    }
    prompt.resize(static_cast<size_t>(written));
    return prompt;
}

static bool tokenize(video_oven_hunyuan_ctx *state, const std::string &prompt, std::vector<llama_token> &tokens) {
    int32_t n_tokens = llama_tokenize(
            state->vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            nullptr,
            0,
            true,
            true
    );
    if (n_tokens == INT32_MIN) {
        return false;
    }
    if (n_tokens < 0) {
        n_tokens = -n_tokens;
    }
    tokens.resize(static_cast<size_t>(n_tokens));
    n_tokens = llama_tokenize(
            state->vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            n_tokens,
            true,
            true
    );
    if (n_tokens < 0) {
        return false;
    }
    tokens.resize(static_cast<size_t>(n_tokens));
    return !tokens.empty();
}

static std::string token_piece(video_oven_hunyuan_ctx *state, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(state->vocab, token, buf, sizeof(buf), 0, true);
    if (n < 0) {
        std::string out(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(state->vocab, token, &out[0], static_cast<int32_t>(out.size()), 0, true);
        if (n <= 0) {
            return {};
        }
        out.resize(static_cast<size_t>(n));
        return out;
    }
    return std::string(buf, static_cast<size_t>(n));
}

extern "C" void *video_oven_hunyuan_load(
        const char *model_path,
        int context_size,
        int gpu_layers,
        int threads
) {
    if (!model_path || !model_path[0]) {
        return nullptr;
    }
    std::call_once(backend_once, [] {
        llama_backend_init();
    });

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpu_layers;
    llama_model *model = llama_model_load_from_file(model_path, model_params);
    if (!model) {
        return nullptr;
    }

    if (context_size <= 0) {
        context_size = 4096;
    }
    if (threads <= 0) {
        threads = 4;
    }
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(context_size);
    ctx_params.n_batch = static_cast<uint32_t>(context_size);
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        llama_model_free(model);
        return nullptr;
    }

    auto *state = new video_oven_hunyuan_ctx {
            model,
            ctx,
            llama_model_get_vocab(model)
    };
    return state;
}

extern "C" char *video_oven_hunyuan_chat(
        void *raw_ctx,
        const char *system_prompt,
        const char *user_content,
        int max_tokens,
        float temperature,
        float top_p,
        int top_k,
        float repeat_penalty
) {
    auto *state = static_cast<video_oven_hunyuan_ctx *>(raw_ctx);
    if (!state || !state->ctx || !state->model || !state->vocab) {
        return nullptr;
    }
    if (max_tokens <= 0) {
        max_tokens = 4096;
    }

    llama_memory_clear(llama_get_memory(state->ctx), true);

    std::vector<llama_token> tokens;
    if (!tokenize(state, build_prompt(state, system_prompt, user_content), tokens)) {
        return nullptr;
    }
    if (tokens.size() >= llama_n_ctx(state->ctx)) {
        return nullptr;
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(sampler_params);
    if (!sampler) {
        return nullptr;
    }
    if (repeat_penalty > 0.0f && repeat_penalty != 1.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, repeat_penalty, 0.0f, 0.0f));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(state->ctx, batch) != 0) {
        llama_sampler_free(sampler);
        return nullptr;
    }

    std::string output;
    for (int i = 0; i < max_tokens; i++) {
        llama_token token = llama_sampler_sample(sampler, state->ctx, -1);
        if (llama_vocab_is_eog(state->vocab, token)) {
            break;
        }
        output += token_piece(state, token);

        if (tokens.size() + static_cast<size_t>(i) + 1 >= llama_n_ctx(state->ctx)) {
            break;
        }
        llama_token next = token;
        llama_batch next_batch = llama_batch_get_one(&next, 1);
        if (llama_decode(state->ctx, next_batch) != 0) {
            llama_sampler_free(sampler);
            return nullptr;
        }
    }

    llama_sampler_free(sampler);
    return copy_result(output);
}

extern "C" void video_oven_hunyuan_free_result(char *text) {
    std::free(text);
}

extern "C" void video_oven_hunyuan_free(void *raw_ctx) {
    auto *state = static_cast<video_oven_hunyuan_ctx *>(raw_ctx);
    if (!state) {
        return;
    }
    if (state->ctx) {
        llama_free(state->ctx);
    }
    if (state->model) {
        llama_model_free(state->model);
    }
    delete state;
}
