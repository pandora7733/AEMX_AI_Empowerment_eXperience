package com.sihoo.aemx.pipeline.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * 등록한 사용자의 목소리인지 가려낸다.
 *
 * 목소리를 숫자 묶음(임베딩)으로 바꿔서, 등록해 둔 것과 얼마나 닮았는지(코사인 유사도)로
 * 판단한다. 말의 내용이 아니라 목소리의 특징을 보므로 한국어 모델이 따로 필요 없다.
 *
 * 등록한 임베딩은 이 기기의 앱 전용 저장소에만 둔다. 밖으로 보내지 않는다.
 */
class SpeakerVerifier(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var extractor: SpeakerEmbeddingExtractor? = null

    @Volatile
    private var enrolled: FloatArray? = SpeakerMath.decode(prefs.getString(KEY_EMBEDDING, null))

    val isEnrolled: Boolean get() = enrolled != null

    /** 이 값 이상이면 같은 사람으로 본다. 실기기에서 본인/타인 점수를 보고 정한다. */
    @Volatile
    var threshold: Float = DEFAULT_THRESHOLD

    /** 모델을 올린다. 28MB 라 백그라운드에서 부른다. */
    fun prepare() {
        if (extractor != null) return
        extractor = SpeakerEmbeddingExtractor(
            assetManager = context.assets,
            config = SpeakerEmbeddingExtractorConfig(model = MODEL_ASSET, numThreads = 2),
        )
        Log.i(TAG, "화자 모델 준비 완료")
    }

    /** 말소리 하나를 임베딩으로 바꾼다. 너무 짧으면 믿을 수 없으므로 null. */
    fun embed(samples: FloatArray): FloatArray? {
        val engine = extractor ?: return null
        if (samples.size < MIN_SAMPLES) return null

        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (engine.isReady(stream)) engine.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    /** 여러 번 말한 것의 평균을 등록한다. 쓸 수 있는 말이 [MIN_ENROLL_COUNT] 개 미만이면 실패. */
    fun enroll(utterances: List<FloatArray>): Boolean {
        val embeddings = utterances.mapNotNull(::embed)
        if (embeddings.size < MIN_ENROLL_COUNT) return false

        val average = SpeakerMath.averageNormalized(embeddings)
        enrolled = average
        prefs.edit().putString(KEY_EMBEDDING, SpeakerMath.encode(average)).apply()
        Log.i(TAG, "화자 등록 완료 (${embeddings.size}개, 차원 ${average.size})")
        return true
    }

    fun clear() {
        enrolled = null
        prefs.edit().remove(KEY_EMBEDDING).apply()
    }

    /** 등록된 목소리와의 유사도. 등록이 없거나 말이 너무 짧으면 null. */
    fun score(samples: FloatArray): Float? {
        val reference = enrolled ?: return null
        val candidate = embed(samples) ?: return null
        return SpeakerMath.cosine(reference, candidate)
    }

    fun release() {
        extractor?.release()
        extractor = null
    }

    companion object {
        private const val TAG = "SpeakerVerifier"
        private const val PREFS = "aemx_speaker"
        private const val KEY_EMBEDDING = "owner_embedding"

        /** STT 프로젝트의 assets 에 둔 모델. 저장소에는 올리지 않는다(`*.onnx` 무시). */
        const val MODEL_ASSET = "speaker/campplus_zh_en.onnx"

        private const val SAMPLE_RATE = 16_000

        /** 0.8초. 이보다 짧은 말은 임베딩이 흔들려서 판단에 쓰지 않는다. */
        private const val MIN_SAMPLES = 12_800

        const val MIN_ENROLL_COUNT = 3

        const val DEFAULT_THRESHOLD = 0.5f
    }
}

/** 임베딩 계산. 네이티브 라이브러리 없이 단위 테스트할 수 있게 따로 뺐다. */
object SpeakerMath {

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "차원이 다르다: ${a.size} / ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (Math.sqrt(normA) * Math.sqrt(normB))).toFloat()
    }

    /** 길이를 1로 맞춘 뒤 평균을 내고, 평균도 다시 길이 1로 맞춘다. */
    fun averageNormalized(embeddings: List<FloatArray>): FloatArray {
        val dim = embeddings.first().size
        val sum = FloatArray(dim)
        for (embedding in embeddings) {
            val norm = Math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
            if (norm == 0f) continue
            for (i in 0 until dim) sum[i] += embedding[i] / norm
        }
        val norm = Math.sqrt(sum.sumOf { (it * it).toDouble() }).toFloat()
        if (norm == 0f) return sum
        for (i in 0 until dim) sum[i] /= norm
        return sum
    }

    fun encode(embedding: FloatArray): String = embedding.joinToString(",")

    fun decode(text: String?): FloatArray? {
        if (text.isNullOrBlank()) return null
        return runCatching { text.split(",").map(String::toFloat).toFloatArray() }.getOrNull()
    }
}
