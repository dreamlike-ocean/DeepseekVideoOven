#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/audio_fifo.h>
#include <stdlib.h>
#include <string.h>

// --- Extract audio to 16kHz mono float samples ---

int ffmpeg_extract_audio(const char *input_path, float **out_samples, int *out_len) {
    int ret = 0;
    AVFormatContext *fmt_ctx = NULL;
    AVCodecContext *codec_ctx = NULL;
    SwrContext *swr = NULL;
    AVPacket *pkt = NULL;
    AVFrame *frame = NULL;
    float *audio_buf = NULL;
    int audio_buf_size = 0;
    int audio_buf_cap = 0;

    ret = avformat_open_input(&fmt_ctx, input_path, NULL, NULL);
    if (ret < 0) goto fail;

    ret = avformat_find_stream_info(fmt_ctx, NULL);
    if (ret < 0) goto fail;

    int audio_idx = av_find_best_stream(fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (audio_idx < 0) { ret = audio_idx; goto fail; }

    const AVCodec *codec = avcodec_find_decoder(fmt_ctx->streams[audio_idx]->codecpar->codec_id);
    if (!codec) { ret = AVERROR_DECODER_NOT_FOUND; goto fail; }

    codec_ctx = avcodec_alloc_context3(codec);
    if (!codec_ctx) { ret = AVERROR(ENOMEM); goto fail; }

    ret = avcodec_parameters_to_context(codec_ctx, fmt_ctx->streams[audio_idx]->codecpar);
    if (ret < 0) goto fail;

    ret = avcodec_open2(codec_ctx, codec, NULL);
    if (ret < 0) goto fail;

    AVChannelLayout out_ch_layout = AV_CHANNEL_LAYOUT_MONO;
    AVChannelLayout in_ch_layout = codec_ctx->ch_layout;
    ret = swr_alloc_set_opts2(&swr,
        &out_ch_layout, AV_SAMPLE_FMT_FLT, 16000,
        &in_ch_layout, codec_ctx->sample_fmt, codec_ctx->sample_rate,
        0, NULL);
    if (ret < 0) goto fail;
    swr_init(swr);

    pkt = av_packet_alloc();
    frame = av_frame_alloc();
    if (!pkt || !frame) { ret = AVERROR(ENOMEM); goto fail; }

    int samples_per_chunk = 16000; // 1 second buffer
    audio_buf_cap = samples_per_chunk;
    audio_buf = av_malloc(audio_buf_cap * sizeof(float));
    if (!audio_buf) { ret = AVERROR(ENOMEM); goto fail; }

    while (av_read_frame(fmt_ctx, pkt) >= 0) {
        if (pkt->stream_index != audio_idx) {
            av_packet_unref(pkt);
            continue;
        }
        ret = avcodec_send_packet(codec_ctx, pkt);
        av_packet_unref(pkt);
        if (ret < 0) break;

        while ((ret = avcodec_receive_frame(codec_ctx, frame)) >= 0) {
            int out_samples_count = frame->nb_samples;
            int needed = audio_buf_size + out_samples_count;
            if (needed > audio_buf_cap) {
                audio_buf_cap = needed * 2;
                float *new_buf = av_realloc(audio_buf, audio_buf_cap * sizeof(float));
                if (!new_buf) { ret = AVERROR(ENOMEM); goto fail; }
                audio_buf = new_buf;
            }
            uint8_t *dst = (uint8_t *)(audio_buf + audio_buf_size);
            int converted = (int)swr_convert(swr, &dst, out_samples_count,
                                             (const uint8_t **)frame->data, frame->nb_samples);
            if (converted < 0) { ret = converted; goto fail; }
            audio_buf_size += converted;
            av_frame_unref(frame);
        }
        if (ret == AVERROR(EAGAIN)) continue;
        if (ret == AVERROR_EOF) { ret = 0; break; }
        if (ret < 0) goto fail;
    }

    // Flush decoder
    avcodec_send_packet(codec_ctx, NULL);
    while ((ret = avcodec_receive_frame(codec_ctx, frame)) >= 0) {
        int out_samples_count = frame->nb_samples;
        int needed = audio_buf_size + out_samples_count;
        if (needed > audio_buf_cap) {
            audio_buf_cap = needed * 2;
            float *new_buf = av_realloc(audio_buf, audio_buf_cap * sizeof(float));
            if (!new_buf) { ret = AVERROR(ENOMEM); goto fail; }
            audio_buf = new_buf;
        }
        uint8_t *dst = (uint8_t *)(audio_buf + audio_buf_size);
        swr_convert(swr, &dst, out_samples_count,
                    (const uint8_t **)frame->data, frame->nb_samples);
        audio_buf_size += frame->nb_samples;
        av_frame_unref(frame);
    }

    *out_samples = audio_buf;
    *out_len = audio_buf_size;
    audio_buf = NULL;
    ret = 0;

fail:
    if (audio_buf) av_free(audio_buf);
    if (frame) av_frame_free(&frame);
    if (pkt) av_packet_free(&pkt);
    if (swr) swr_free(&swr);
    if (codec_ctx) avcodec_free_context(&codec_ctx);
    if (fmt_ctx) avformat_close_input(&fmt_ctx);
    return ret;
}

// --- Burn ASS subtitles into video ---

static int open_encoder(AVCodecContext *dec_ctx, AVStream *out_stream, AVCodecContext **enc_ctx) {
    const AVCodec *encoder = avcodec_find_encoder(AV_CODEC_ID_H264);
    if (!encoder) encoder = avcodec_find_encoder_by_name("libx264");
    if (!encoder) return AVERROR_ENCODER_NOT_FOUND;

    *enc_ctx = avcodec_alloc_context3(encoder);
    if (!*enc_ctx) return AVERROR(ENOMEM);

    (*enc_ctx)->width = dec_ctx->width;
    (*enc_ctx)->height = dec_ctx->height;
    (*enc_ctx)->sample_aspect_ratio = dec_ctx->sample_aspect_ratio;
    (*enc_ctx)->pix_fmt = AV_PIX_FMT_YUV420P;
    (*enc_ctx)->time_base = av_inv_q(dec_ctx->framerate);
    (*enc_ctx)->framerate = dec_ctx->framerate;

    if (out_stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
        (*enc_ctx)->time_base = dec_ctx->time_base;
    }

    out_stream->time_base = (*enc_ctx)->time_base;

    int ret = avcodec_open2(*enc_ctx, encoder, NULL);
    if (ret < 0) return ret;
    ret = avcodec_parameters_from_context(out_stream->codecpar, *enc_ctx);
    return ret;
}

static int init_subtitles_filter(AVFilterGraph **graph, AVFilterContext **src, AVFilterContext **sink,
                                  AVCodecContext *dec_ctx, const char *ass_path) {
    *graph = avfilter_graph_alloc();
    if (!*graph) return AVERROR(ENOMEM);

    const AVFilter *buffersrc = avfilter_get_by_name("buffer");
    const AVFilter *buffersink = avfilter_get_by_name("buffersink");

    char args[512];
    snprintf(args, sizeof(args),
             "video_size=%dx%d:pix_fmt=%d:time_base=%d/%d:pixel_aspect=%d/%d:frame_rate=%d/%d",
             dec_ctx->width, dec_ctx->height, dec_ctx->pix_fmt,
             dec_ctx->time_base.num, dec_ctx->time_base.den,
             dec_ctx->sample_aspect_ratio.num, dec_ctx->sample_aspect_ratio.den,
             dec_ctx->framerate.num, dec_ctx->framerate.den);

    int ret = avfilter_graph_create_filter(src, buffersrc, "in", args, NULL, *graph);
    if (ret < 0) return ret;

    ret = avfilter_graph_create_filter(sink, buffersink, "out", NULL, NULL, *graph);
    if (ret < 0) return ret;

    enum AVPixelFormat pix_fmts[] = { AV_PIX_FMT_YUV420P, AV_PIX_FMT_NONE };
    ret = av_opt_set_int_list(*sink, "pix_fmts", pix_fmts, AV_PIX_FMT_NONE, AV_OPT_SEARCH_CHILDREN);
    if (ret < 0) return ret;

    char filter_desc[1024];
    snprintf(filter_desc, sizeof(filter_desc),
             "subtitles=%s:force_style='FontName=Arial,FontSize=24,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,Outline=2'",
             ass_path);

    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs = avfilter_inout_alloc();
    if (!outputs || !inputs) { avfilter_inout_free(&outputs); avfilter_inout_free(&inputs); return AVERROR(ENOMEM); }

    outputs->name = av_strdup("in");
    outputs->filter_ctx = *src;
    outputs->pad_idx = 0;
    outputs->next = NULL;

    inputs->name = av_strdup("out");
    inputs->filter_ctx = *sink;
    inputs->pad_idx = 0;
    inputs->next = NULL;

    ret = avfilter_graph_parse_ptr(*graph, filter_desc, &inputs, &outputs, NULL);
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);
    if (ret < 0) return ret;

    ret = avfilter_graph_config(*graph, NULL);
    return ret;
}

int ffmpeg_burn_subtitles(const char *input_path, const char *ass_path, const char *output_path) {
    int ret = 0;
    AVFormatContext *in_fmt = NULL, *out_fmt = NULL;
    AVCodecContext *dec_ctx = NULL, *enc_ctx = NULL;
    AVFilterGraph *filter_graph = NULL;
    AVFilterContext *src_ctx = NULL, *sink_ctx = NULL;
    AVPacket *pkt = NULL;
    AVFrame *frame = NULL, *filt_frame = NULL;
    int *stream_map = NULL;

    ret = avformat_open_input(&in_fmt, input_path, NULL, NULL);
    if (ret < 0) goto fail;
    ret = avformat_find_stream_info(in_fmt, NULL);
    if (ret < 0) goto fail;

    int video_idx = av_find_best_stream(in_fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (video_idx < 0) { ret = video_idx; goto fail; }

    ret = avformat_alloc_output_context2(&out_fmt, NULL, NULL, output_path);
    if (ret < 0) goto fail;

    stream_map = av_calloc(in_fmt->nb_streams, sizeof(int));
    if (!stream_map) { ret = AVERROR(ENOMEM); goto fail; }

    for (unsigned i = 0; i < in_fmt->nb_streams; i++) {
        if ((int)i == video_idx) {
            AVStream *out_stream = avformat_new_stream(out_fmt, NULL);
            if (!out_stream) { ret = AVERROR(ENOMEM); goto fail; }
            stream_map[i] = out_stream->index;
        } else {
            AVStream *in_stream = in_fmt->streams[i];
            AVStream *out_stream = avformat_new_stream(out_fmt, NULL);
            if (!out_stream) { ret = AVERROR(ENOMEM); goto fail; }
            ret = avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar);
            if (ret < 0) goto fail;
            out_stream->time_base = in_stream->time_base;
            stream_map[i] = out_stream->index;
        }
    }

    ret = avio_open(&out_fmt->pb, output_path, AVIO_FLAG_WRITE);
    if (ret < 0) goto fail;
    ret = avformat_write_header(out_fmt, NULL);
    if (ret < 0) goto fail;

    // Setup video decoder
    AVCodecParameters *video_par = in_fmt->streams[video_idx]->codecpar;
    const AVCodec *decoder = avcodec_find_decoder(video_par->codec_id);
    if (!decoder) { ret = AVERROR_DECODER_NOT_FOUND; goto fail; }
    dec_ctx = avcodec_alloc_context3(decoder);
    if (!dec_ctx) { ret = AVERROR(ENOMEM); goto fail; }
    ret = avcodec_parameters_to_context(dec_ctx, video_par);
    if (ret < 0) goto fail;
    ret = avcodec_open2(dec_ctx, decoder, NULL);
    if (ret < 0) goto fail;

    // Setup video encoder
    ret = open_encoder(dec_ctx, out_fmt->streams[stream_map[video_idx]], &enc_ctx);
    if (ret < 0) goto fail;

    // Setup subtitles filter
    ret = init_subtitles_filter(&filter_graph, &src_ctx, &sink_ctx, dec_ctx, ass_path);
    if (ret < 0) goto fail;

    pkt = av_packet_alloc();
    frame = av_frame_alloc();
    filt_frame = av_frame_alloc();
    if (!pkt || !frame || !filt_frame) { ret = AVERROR(ENOMEM); goto fail; }

    int64_t frame_pts = 0;
    while (av_read_frame(in_fmt, pkt) >= 0) {
        if (pkt->stream_index == video_idx) {
            ret = avcodec_send_packet(dec_ctx, pkt);
            av_packet_unref(pkt);
            if (ret < 0) break;

            while ((ret = avcodec_receive_frame(dec_ctx, frame)) >= 0) {
                frame->pts = frame->best_effort_timestamp;
                ret = av_buffersrc_add_frame_flags(src_ctx, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
                if (ret < 0) goto fail;

                while ((ret = av_buffersink_get_frame(sink_ctx, filt_frame)) >= 0) {
                    filt_frame->pts = frame_pts++;
                    ret = avcodec_send_frame(enc_ctx, filt_frame);
                    if (ret < 0) goto fail;
                    av_frame_unref(filt_frame);

                    while ((ret = avcodec_receive_packet(enc_ctx, pkt)) >= 0) {
                        pkt->stream_index = stream_map[video_idx];
                        av_packet_rescale_ts(pkt, enc_ctx->time_base,
                                            out_fmt->streams[stream_map[video_idx]]->time_base);
                        ret = av_interleaved_write_frame(out_fmt, pkt);
                        if (ret < 0) goto fail;
                        av_packet_unref(pkt);
                    }
                    if (ret == AVERROR(EAGAIN)) continue;
                    if (ret < 0) goto fail;
                }
                if (ret == AVERROR(EAGAIN)) continue;
                if (ret < 0) goto fail;
                av_frame_unref(frame);
            }
            if (ret == AVERROR(EAGAIN)) continue;
            if (ret == AVERROR_EOF) break;
            if (ret < 0) goto fail;
        } else {
            // Copy non-video packets
            int out_idx = stream_map[pkt->stream_index];
            av_packet_rescale_ts(pkt, in_fmt->streams[pkt->stream_index]->time_base,
                                 out_fmt->streams[out_idx]->time_base);
            pkt->stream_index = out_idx;
            ret = av_interleaved_write_frame(out_fmt, pkt);
            av_packet_unref(pkt);
            if (ret < 0) goto fail;
        }
    }

    av_write_trailer(out_fmt);
    ret = 0;

fail:
    if (stream_map) av_free(stream_map);
    if (filt_frame) av_frame_free(&filt_frame);
    if (frame) av_frame_free(&frame);
    if (pkt) av_packet_free(&pkt);
    if (filter_graph) avfilter_graph_free(&filter_graph);
    if (enc_ctx) avcodec_free_context(&enc_ctx);
    if (dec_ctx) avcodec_free_context(&dec_ctx);
    if (out_fmt) {
        if (out_fmt->pb) avio_closep(&out_fmt->pb);
        avformat_free_context(out_fmt);
    }
    if (in_fmt) avformat_close_input(&in_fmt);
    return ret;
}
